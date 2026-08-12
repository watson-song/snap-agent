#!/usr/bin/env python3
"""
Universal CodeGraph Generator for any Java/Spring Boot project.

Usage:
    python3 generate-codegraph.py \
        --project-root /path/to/project \
        --output-dir data/codegraph \
        --project-name my-project

Features:
    - Multi-module scanning (Maven/Gradle)
    - Multi-level entity identification (@TableName, entity/ dir, naming)
    - Key source code extraction for diagnosis support
    - H2 database + CSV output
    - Cross-validation with knowledge documents (optional)

Author: SnapAgent
Version: 1.0.0
"""

import os
import sys
import re
import argparse
import json
import sqlite3
from pathlib import Path
from collections import defaultdict

# ============================================================
# Configuration
# ============================================================

EXCLUDED_DIRS = {
    'target', 'build', '.git', '.idea', '.gradle',
    'node_modules', '__pycache__', '.mvn', 'bin',
    'test', 'tests', '.worktrees'
}

EXCLUDED_PACKAGES = {
    'generated', 'protobuf', 'grpc', 'openapi'
}


# ============================================================
# Project Scanner
# ============================================================

def detect_build_tool(project_root):
    """Detect Maven or Gradle build tool."""
    if os.path.exists(os.path.join(project_root, 'pom.xml')):
        return 'maven'
    elif os.path.exists(os.path.join(project_root, 'build.gradle')):
        return 'gradle'
    return None


def scan_modules(project_root, build_tool):
    """Scan project modules from pom.xml or build.gradle."""
    modules = []

    if build_tool == 'maven':
        pom_path = os.path.join(project_root, 'pom.xml')
        with open(pom_path, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
        # Extract <module>xxx</module>
        module_pattern = r'<module>([^<]+)</module>'
        modules = re.findall(module_pattern, content)

    if not modules:
        # Single module project or couldn't parse
        modules = ['.']

    return modules


def find_java_files(module_root):
    """Find all Java source files in a module, excluding test and build dirs."""
    java_files = []
    src_dir = os.path.join(module_root, 'src', 'main', 'java')
    if not os.path.exists(src_dir):
        return java_files

    for root, dirs, files in os.walk(src_dir):
        # Filter out excluded directories
        dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS and not d.startswith('.')]

        for f in files:
            if f.endswith('.java'):
                java_files.append(os.path.join(root, f))

    return java_files


# ============================================================
# Java File Parser
# ============================================================

def parse_java_file(java_file, project_root):
    """Parse a Java file and extract class metadata."""
    try:
        with open(java_file, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
    except Exception:
        return None

    rel_path = os.path.relpath(java_file, project_root)

    # Extract package
    package_match = re.search(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
    package_name = package_match.group(1) if package_match else ''

    # Extract class/interface/enum name
    class_match = re.search(
        r'(?:public\s+)?(?:abstract\s+)?(?:final\s+)?(class|interface|enum|@interface)\s+(\w+)',
        content
    )
    if not class_match:
        return None

    class_type = class_match.group(1)
    class_name = class_match.group(2)
    fqn = f"{package_name}.{class_name}" if package_name else class_name

    # Determine detailed type
    detailed_type = determine_class_type(class_name, content, rel_path)

    # Extract @TableName
    table_name = None
    table_match = re.search(r'@TableName\s*\(\s*"([^"]+)"\s*\)', content)
    if table_match:
        table_name = table_match.group(1)

    # Extract annotations
    annotations = re.findall(r'@(\w+)', content[:2000])

    # Extract dependencies (@Autowired, @Resource, constructor injection)
    dependencies = extract_dependencies(content)

    # Extract public method signatures
    public_methods = extract_public_methods(content)

    # Extract key source code for diagnosis
    source_code = extract_key_code(content, max_length=3000)

    return {
        'node_id': fqn,
        'name': class_name,
        'type': detailed_type,
        'base_type': class_type,
        'package': package_name,
        'fqn': fqn,
        'file_path': rel_path,
        'table_name': table_name,
        'annotations': annotations,
        'dependencies': dependencies,
        'public_methods': public_methods,
        'source_code': source_code,
        'source_length': len(source_code) if source_code else 0
    }


def determine_class_type(class_name, content, rel_path):
    """Determine the detailed type of a class using multi-level strategy."""
    # Level 1: Annotations (most reliable)
    if '@RestController' in content or '@Controller' in content:
        return 'Controller'
    if '@Service' in content:
        return 'Service'
    if '@Repository' in content:
        return 'Repository'
    if '@Configuration' in content:
        return 'Configuration'
    if '@Component' in content:
        return 'Component'
    if '@Aspect' in content:
        return 'Aspect'
    if '@TableName' in content or '@Entity' in content or '@Table' in content:
        return 'Entity'
    if re.search(r'extends\s+BaseMapper', content):
        return 'Mapper'

    # Level 2: Naming conventions
    if class_name.endswith('Controller'):
        return 'Controller'
    if class_name.endswith('ServiceImpl') or class_name.endswith('Service'):
        return 'Service'
    if class_name.endswith('Mapper') or class_name.endswith('Dao'):
        return 'Mapper'
    if class_name.endswith('DTO') or class_name.endswith('Dto'):
        return 'DTO'
    if class_name.endswith('VO') or class_name.endswith('Vo'):
        return 'VO'
    if class_name.endswith('Entity'):
        return 'Entity'
    if class_name.endswith('Enum') or class_name.endswith('Status'):
        return 'Enum'
    if class_name.endswith('Config') or class_name.endswith('Configuration'):
        return 'Configuration'
    if class_name.endswith('Job') or class_name.endswith('Task'):
        return 'Job'
    if class_name.endswith('Advice'):
        return 'Advice'
    if class_name.endswith('Exception'):
        return 'Exception'
    if class_name.endswith('Properties'):
        return 'Properties'

    # Level 3: Directory-based
    if '/entity/' in rel_path or '/model/' in rel_path or '/domain/' in rel_path:
        return 'Entity'
    if '/controller/' in rel_path or '/web/' in rel_path:
        return 'Controller'
    if '/service/' in rel_path:
        return 'Service'
    if '/mapper/' in rel_path or '/dao/' in rel_path:
        return 'Mapper'
    if '/dto/' in rel_path or '/vo/' in rel_path:
        return 'DTO'
    if '/config/' in rel_path:
        return 'Configuration'
    if '/enums/' in rel_path or '/constant/' in rel_path:
        return 'Enum'

    # Level 4: Interface/Base class
    if re.search(r'extends\s+\w*Mapper', content):
        return 'Mapper'
    if re.search(r'implements\s+Serializable', content) and '@' not in content[:500]:
        return 'Entity'  # Likely a data class

    # Default
    return 'Class'


def extract_dependencies(content):
    """Extract injected dependencies."""
    deps = []

    # @Autowired fields
    autowired = re.findall(r'@Autowired\s+(?:private|protected|public)?\s+(\w+)\s+(\w+)', content)
    for type_name, field_name in autowired:
        deps.append({'type': type_name, 'field': field_name, 'inject': 'Autowired'})

    # @Resource fields
    resource = re.findall(r'@Resource\s+(?:private|protected|public)?\s+(\w+)\s+(\w+)', content)
    for type_name, field_name in resource:
        deps.append({'type': type_name, 'field': field_name, 'inject': 'Resource'})

    # Constructor injection (simplified)
    constructor_deps = re.findall(r'private\s+final\s+(\w+)\s+(\w+)\s*;', content)
    for type_name, field_name in constructor_deps:
        deps.append({'type': type_name, 'field': field_name, 'inject': 'Constructor'})

    return deps


def extract_public_methods(content):
    """Extract public method signatures."""
    methods = []
    pattern = r'(?:public|protected)\s+(?:static\s+)?(?:synchronized\s+)?([\w<>\[\],\s]+?)\s+(\w+)\s*\(([^)]*)\)'
    for match in re.finditer(pattern, content):
        return_type = match.group(1).strip()
        method_name = match.group(2)
        params = match.group(3).strip()

        # Skip constructors (return type == class name)
        if return_type == method_name:
            continue
        # Skip common non-business methods
        if method_name in ('toString', 'hashCode', 'equals', 'getClass', 'clone',
                           'notify', 'notifyAll', 'wait', 'finalize'):
            continue

        methods.append({
            'return_type': return_type,
            'name': method_name,
            'params': params
        })

    return methods


def extract_key_code(content, max_length=3000):
    """Extract key source code for diagnosis support."""
    key_parts = []

    # 1. Class Javadoc
    class_javadoc = re.search(r'/\*\*\s*\n(.*?)\*/\s*(?:public|abstract|final)?\s*(?:class|interface|enum)',
                              content, re.DOTALL)
    if class_javadoc:
        javadoc_text = class_javadoc.group(1).strip()
        # Clean up
        lines = [l.strip().lstrip('* ').strip() for l in javadoc_text.split('\n')]
        clean_lines = [l for l in lines if l and not l.startswith('@')]
        if clean_lines:
            key_parts.append('/**\n * ' + '\n * '.join(clean_lines[:5]) + '\n */')

    # 2. Class annotations
    class_annots = re.findall(r'(@\w+(?:\([^)]*\))?)\s*\n\s*(?:public|abstract|final)?\s*(?:class|interface|enum)',
                              content)
    if not class_annots:
        class_annots = re.findall(r'(@\w+(?:\([^)]*\))?)', content[:1000])
    if class_annots:
        key_parts.append('\n'.join(class_annots[:10]))

    # 3. Field definitions (important ones)
    fields = re.findall(
        r'((?:@\w+(?:\([^)]*\))?\s*)*)(?:private|protected)\s+(?:final\s+)?(?:static\s+)?([\w<>\[\],\s]+?)\s+(\w+)\s*(?:=\s*[^;]+)?;',
        content
    )
    if fields:
        field_lines = []
        for annot_str, type_str, name in fields[:20]:
            annot_clean = annot_str.strip()
            line = f"{annot_clean}\nprivate {type_str.strip()} {name};" if annot_clean else f"private {type_str.strip()} {name};"
            field_lines.append(line)
        key_parts.append('// Fields\n' + '\n'.join(field_lines))

    # 4. Public method signatures with Javadoc
    method_pattern = r'(/\*\*.*?\*/\s*)?(public|protected)\s+(?:static\s+)?(?:synchronized\s+)?([\w<>\[\],\s]+?)\s+(\w+)\s*\(([^)]*)\)(?:\s+throws\s+[\w,\s]+)?\s*\{'
    methods = re.findall(method_pattern, content, re.DOTALL)
    if methods:
        method_lines = []
        for javadoc, modifier, return_type, method_name, params in methods[:15]:
            if method_name in ('toString', 'hashCode', 'equals', 'getClass', 'clone'):
                continue
            if javadoc:
                # Clean javadoc
                jd_lines = [l.strip().lstrip('* ').strip() for l in javadoc.split('\n')]
                jd_clean = [l for l in jd_lines if l and l != '/' and l != '*/']
                if jd_clean:
                    method_lines.append('  /** ' + ' '.join(jd_clean[:3]) + ' */')
            sig = f"  {modifier} {return_type.strip()} {method_name}({params.strip()})"
            method_lines.append(sig)
        key_parts.append('// Methods\n' + '\n'.join(method_lines))

    # 5. Key business comments
    biz_keywords = ['业务', '规则', '计算', '公式', '条件', '安全库存', '提前期',
                    '注意', 'TODO', 'FIXME', 'HACK', 'WARNING']
    biz_comments = []
    for line in content.split('\n'):
        line = line.strip()
        if line.startswith('//'):
            comment = line[2:].strip()
            if any(kw in comment for kw in biz_keywords):
                biz_comments.append(f"  {line}")
    if biz_comments:
        key_parts.append('// Business Logic\n' + '\n'.join(biz_comments[:10]))

    result = '\n\n'.join(key_parts)
    if len(result) > max_length:
        result = result[:max_length] + '\n  // ... (truncated)'

    return result


# ============================================================
# Dependency Extraction
# ============================================================

def extract_edges(nodes):
    """Extract dependency edges between classes."""
    edges = []
    fqn_set = {n['fqn'] for n in nodes}
    name_to_fqn = {}
    for n in nodes:
        name_to_fqn[n['name']] = n['fqn']

    for node in nodes:
        for dep in node['dependencies']:
            dep_type = dep['type']
            # Resolve to FQN
            target_fqn = None
            if dep_type in fqn_set:
                target_fqn = dep_type
            elif dep_type in name_to_fqn:
                target_fqn = name_to_fqn[dep_type]

            if target_fqn:
                edges.append({
                    'source': node['fqn'],
                    'target': target_fqn,
                    'type': 'DEPENDS_ON',
                    'detail': dep['inject']
                })

        # Import-based edges
        imports = re.findall(r'import\s+([\w.]+)\s*;', 
                            open(node['file_path'], 'r', encoding='utf-8', errors='ignore').read()
                            if os.path.exists(node['file_path']) else '')

    return edges


def extract_import_edges(java_file, project_root, fqn_set, name_to_fqn):
    """Extract edges from import statements."""
    edges = []
    try:
        with open(java_file, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
    except:
        return edges

    # Get source class FQN
    package_match = re.search(r'^package\s+([\w.]+)\s*;', content, re.MULTILINE)
    package_name = package_match.group(1) if package_match else ''
    class_match = re.search(r'(?:public\s+)?(?:abstract\s+)?(?:class|interface|enum)\s+(\w+)', content)
    if not class_match:
        return edges
    source_fqn = f"{package_name}.{class_match.group(1)}" if package_name else class_match.group(1)

    # Parse imports
    for import_match in re.finditer(r'import\s+([\w.]+)\s*;', content):
        imported = import_match.group(1)
        if imported in fqn_set:
            edges.append({
                'source': source_fqn,
                'target': imported,
                'type': 'IMPORTS',
                'detail': ''
            })

    return edges


# ============================================================
# Database Writer
# ============================================================

def write_to_sqlite(nodes, edges, output_dir, project_name):
    """Write nodes and edges to SQLite database."""
    os.makedirs(output_dir, exist_ok=True)
    db_path = os.path.join(output_dir, f"{project_name}-codegraph.db")

    conn = sqlite3.connect(db_path)
    c = conn.cursor()

    # Create tables
    c.execute('''CREATE TABLE IF NOT EXISTS CODE_GRAPH_NODES (
        NODE_ID VARCHAR(512) PRIMARY KEY,
        NAME VARCHAR(256) NOT NULL,
        TYPE VARCHAR(64) NOT NULL,
        PACKAGE VARCHAR(512),
        FILE_PATH VARCHAR(1024),
        TABLE_NAME VARCHAR(256),
        SOURCE_CODE TEXT,
        SOURCE_LENGTH INTEGER
    )''')

    c.execute('''CREATE TABLE IF NOT EXISTS CODE_GRAPH_EDGES (
        SOURCE VARCHAR(512) NOT NULL,
        TARGET VARCHAR(512) NOT NULL,
        TYPE VARCHAR(64) NOT NULL,
        DETAIL VARCHAR(256),
        PRIMARY KEY (SOURCE, TARGET, TYPE)
    )''')

    # Insert nodes
    for node in nodes:
        c.execute(
            'INSERT OR REPLACE INTO CODE_GRAPH_NODES VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
            (node['node_id'], node['name'], node['type'], node['package'],
             node['file_path'], node.get('table_name'), node.get('source_code', ''),
             node.get('source_length', 0))
        )

    # Insert edges
    for edge in edges:
        c.execute(
            'INSERT OR REPLACE INTO CODE_GRAPH_EDGES VALUES (?, ?, ?, ?)',
            (edge['source'], edge['target'], edge['type'], edge.get('detail', ''))
        )

    conn.commit()
    conn.close()

    return db_path


def write_csv(nodes, edges, output_dir):
    """Write nodes and edges to CSV files."""
    os.makedirs(output_dir, exist_ok=True)

    # Nodes CSV
    with open(os.path.join(output_dir, 'nodes.csv'), 'w', encoding='utf-8') as f:
        f.write('node_id,name,type,package,file_path,table_name,source_length\n')
        for node in nodes:
            f.write(f"{node['node_id']},{node['name']},{node['type']},"
                    f"{node['package']},{node['file_path']},"
                    f"{node.get('table_name','')},{node.get('source_length',0)}\n")

    # Edges CSV
    with open(os.path.join(output_dir, 'edges.csv'), 'w', encoding='utf-8') as f:
        f.write('source,target,type,detail\n')
        for edge in edges:
            f.write(f"{edge['source']},{edge['target']},{edge['type']},{edge.get('detail','')}\n")


# ============================================================
# Main
# ============================================================

def main():
    parser = argparse.ArgumentParser(description='Universal CodeGraph Generator')
    parser.add_argument('--project-root', required=True, help='Project root directory')
    parser.add_argument('--output-dir', default='data/codegraph', help='Output directory')
    parser.add_argument('--project-name', default=None, help='Project name (auto-detect if not set)')

    args = parser.parse_args()

    project_root = os.path.abspath(args.project_root)
    output_dir = os.path.join(project_root, args.output_dir)

    # Auto-detect project name
    project_name = args.project_name or os.path.basename(project_root)

    print(f"=== CodeGraph Generator v1.0.0 ===")
    print(f"Project: {project_name}")
    print(f"Root: {project_root}")
    print(f"Output: {output_dir}")
    print()

    # Step 1: Detect build tool
    build_tool = detect_build_tool(project_root)
    print(f"[1/6] Build tool: {build_tool or 'unknown'}")

    # Step 2: Scan modules
    modules = scan_modules(project_root, build_tool)
    print(f"[2/6] Modules: {len(modules)} - {modules}")

    # Step 3: Scan Java files
    all_java_files = []
    for module in modules:
        module_root = os.path.join(project_root, module)
        java_files = find_java_files(module_root)
        all_java_files.extend(java_files)
        print(f"  - {module}: {len(java_files)} files")

    print(f"[3/6] Total Java files: {len(all_java_files)}")

    # Step 4: Parse Java files
    nodes = []
    errors = []
    for i, java_file in enumerate(all_java_files):
        node = parse_java_file(java_file, project_root)
        if node:
            nodes.append(node)
        else:
            errors.append(java_file)

        if (i + 1) % 500 == 0:
            print(f"  ... parsed {i + 1}/{len(all_java_files)} files")

    print(f"[4/6] Parsed {len(nodes)} classes ({len(errors)} errors)")

    # Step 5: Extract edges
    fqn_set = {n['fqn'] for n in nodes}
    name_to_fqn = {}
    for n in nodes:
        name_to_fqn[n['name']] = n['fqn']

    # Dependency edges (from @Autowired/@Resource)
    dep_edges = extract_edges(nodes)

    # Import edges
    import_edges = []
    for node in nodes:
        java_file = os.path.join(project_root, node['file_path'])
        edges = extract_import_edges(java_file, project_root, fqn_set, name_to_fqn)
        import_edges.extend(edges)

    all_edges = dep_edges + import_edges
    # Deduplicate
    seen = set()
    unique_edges = []
    for e in all_edges:
        key = (e['source'], e['target'], e['type'])
        if key not in seen:
            seen.add(key)
            unique_edges.append(e)

    print(f"[5/6] Extracted {len(unique_edges)} edges ({len(dep_edges)} deps + {len(import_edges)} imports)")

    # Step 6: Write output
    db_path = write_to_sqlite(nodes, unique_edges, output_dir, project_name)
    write_csv(nodes, unique_edges, output_dir)

    print(f"[6/6] Output written:")
    print(f"  - Database: {db_path}")
    print(f"  - Nodes CSV: {os.path.join(output_dir, 'nodes.csv')}")
    print(f"  - Edges CSV: {os.path.join(output_dir, 'edges.csv')}")

    # Summary
    print()
    print("=== Summary ===")
    type_counts = defaultdict(int)
    for n in nodes:
        type_counts[n['type']] += 1
    for t, count in sorted(type_counts.items(), key=lambda x: -x[1]):
        print(f"  {t}: {count}")

    total_source = sum(n.get('source_length', 0) for n in nodes)
    print(f"  Total source code: {total_source / 1024:.0f} KB")
    print(f"  DB size: {os.path.getsize(db_path) / 1024 / 1024:.1f} MB")


if __name__ == '__main__':
    main()
