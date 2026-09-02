#!/usr/bin/env python3
"""
CodeGraph 代码图谱生成器（通用版）
适用于任何 Spring Boot 项目

用法:
    python3 generate-codegraph.py [OPTIONS]

选项:
    -r, --project-root DIR    项目根目录（默认：当前目录）
    -o, --output PATH         输出路径（默认：./data/codegraph）
    -p, --packages PKG        扫描的包名（逗号分隔，默认：扫描所有）
    -h, --help                显示帮助
"""

import os
import re
import sqlite3
import argparse
from pathlib import Path
from typing import List, Dict, Set, Tuple


class CodeGraphGenerator:
    """代码图谱生成器"""

    def __init__(self, project_root: str, output_dir: str, scan_packages: List[str] = None):
        self.project_root = Path(project_root).resolve()
        self.output_dir = Path(output_dir).resolve()
        self.scan_packages = scan_packages or []
        
        self.nodes = []
        self.edges = []
        self.node_set = set()
        self.edge_set = set()

    def scan_java_files(self):
        """扫描所有 Java 文件"""
        print("扫描 Java 文件...")
        
        # 查找所有 src/main/java 目录
        src_dirs = []
        for pom_file in self.project_root.rglob("pom.xml"):
            module_dir = pom_file.parent
            java_dir = module_dir / "src" / "main" / "java"
            if java_dir.exists():
                src_dirs.append(java_dir)
        
        if not src_dirs:
            # 回退：扫描项目根目录
            src_dirs = [self.project_root / "src" / "main" / "java"]
        
        print(f"找到 {len(src_dirs)} 个源码目录")
        
        for src_dir in src_dirs:
            for java_file in src_dir.rglob("*.java"):
                self.process_java_file(java_file)
        
        print(f"找到 {len(self.nodes)} 个类")

    def process_java_file(self, java_file: Path):
        """处理单个 Java 文件"""
        try:
            content = java_file.read_text(encoding='utf-8')
            
            # 提取包名
            package_match = re.search(r'package\s+([\w.]+);', content)
            package = package_match.group(1) if package_match else ""
            
            # 如果指定了 scan_packages，过滤包
            if self.scan_packages:
                if not any(package.startswith(pkg) for pkg in self.scan_packages):
                    return
            
            # 提取类名
            class_match = re.search(r'(?:public\s+)?(?:class|interface|enum)\s+(\w+)', content)
            if not class_match:
                return
            
            class_name = class_match.group(1)
            node_id = f"{package}.{class_name}" if package else class_name
            
            # 确定类类型
            class_type = self.detect_class_type(class_name, content)
            
            # 提取关键代码
            source_code = self.extract_key_code(content)
            
            # 添加节点
            if node_id not in self.node_set:
                self.nodes.append({
                    'node_id': node_id,
                    'name': class_name,
                    'type': class_type,
                    'package_name': package,
                    'source_code': source_code,
                    'file_path': str(java_file)
                })
                self.node_set.add(node_id)
            
            # 提取依赖关系
            self.extract_dependencies(content, node_id, package)
            
        except Exception as e:
            print(f"处理文件失败 {java_file}: {e}")

    def detect_class_type(self, class_name: str, content: str) -> str:
        """检测类类型"""
        if 'Controller' in class_name:
            return 'Controller'
        elif 'Service' in class_name and 'Impl' not in class_name:
            return 'Service'
        elif 'ServiceImpl' in class_name or 'Impl' in class_name:
            return 'ServiceImpl'
        elif 'Mapper' in class_name or 'Dao' in class_name:
            return 'Mapper'
        elif any(x in class_name for x in ['Entity', 'DO', 'PO']):
            return 'Entity'
        elif 'DTO' in class_name or 'VO' in class_name:
            return 'DTO'
        elif 'interface ' in content[:500]:
            return 'Interface'
        else:
            return 'Class'

    def extract_key_code(self, content: str) -> str:
        """提取关键代码片段"""
        key_parts = []
        
        # 提取类 Javadoc
        class_comment_match = re.search(r'/\*\*\s*(.*?)\s*\*/\s*(?:public\s+)?(?:class|interface|enum)', content, re.DOTALL)
        if class_comment_match:
            comment = class_comment_match.group(1).strip()
            key_parts.append(f"// Class Javadoc\n{comment}")
        
        # 提取字段定义
        fields = re.findall(r'(?:@(\w+))?\s*(private|protected|public)\s+([\w<>\[\]]+)\s+(\w+)(?:\s*=\s*[^;]+)?;', content)
        if fields:
            field_lines = []
            for ann, modifier, type, name in fields[:20]:
                line = f"{modifier} {type} {name};"
                if ann:
                    line = f"@{ann}\n{line}"
                field_lines.append(line)
            key_parts.append(f"// Fields ({len(fields)})\n" + '\n'.join(field_lines))
        
        # 提取 public 方法签名
        methods = re.findall(r'public\s+(?:static\s+)?(?:synchronized\s+)?[\w<>\[\]]+\s+(\w+)\s*\([^)]*\)(?:\s+throws\s+[\w,]+)?', content)
        if methods:
            key_parts.append(f"// Public Methods ({len(methods)})\n" + '\n'.join(methods[:15]))
        
        result = '\n\n'.join(key_parts)
        return result[:3000] if len(result) > 3000 else result

    def extract_dependencies(self, content: str, source_id: str, package: str):
        """提取依赖关系"""
        # 提取 import 依赖
        imports = re.findall(r'import\s+([\w.]+);', content)
        for imp in imports:
            if imp.startswith(package.split('.')[0]) and imp != package:
                edge_key = (source_id, imp, 'IMPORTS')
                if edge_key not in self.edge_set:
                    self.edges.append({
                        'source_id': source_id,
                        'target_id': imp,
                        'type': 'IMPORTS'
                    })
                    self.edge_set.add(edge_key)
        
        # 提取 @Autowired 和 @Resource 依赖
        autowired = re.findall(r'@Autowired\s+\w+\s+(\w+)', content)
        resources = re.findall(r'@Resource\s+\w+\s+(\w+)', content)
        
        for field in autowired + resources:
            edge_key = (source_id, field, 'DEPENDS_ON')
            if edge_key not in self.edge_set:
                self.edges.append({
                    'source_id': source_id,
                    'target_id': field,
                    'type': 'DEPENDS_ON'
                })
                self.edge_set.add(edge_key)

    def generate_sqlite_db(self):
        """生成 SQLite 数据库"""
        print("生成 SQLite 数据库...")
        self.output_dir.mkdir(parents=True, exist_ok=True)
        db_path = self.output_dir / "codegraph.db"
        
        conn = sqlite3.connect(str(db_path))
        cursor = conn.cursor()
        
        # 创建表
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS code_graph_nodes (
                node_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                package_name TEXT,
                source_code TEXT,
                file_path TEXT
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS code_graph_edges (
                source_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                type TEXT NOT NULL,
                PRIMARY KEY (source_id, target_id, type)
            )
        ''')
        
        # 创建索引
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_nodes_name ON code_graph_nodes(name)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_nodes_type ON code_graph_nodes(type)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_nodes_package ON code_graph_nodes(package_name)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_edges_source ON code_graph_edges(source_id)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_edges_target ON code_graph_edges(target_id)')
        
        # 插入数据
        for node in self.nodes:
            cursor.execute(
                'INSERT OR REPLACE INTO code_graph_nodes VALUES (?, ?, ?, ?, ?, ?)',
                (node['node_id'], node['name'], node['type'], 
                 node['package_name'], node['source_code'], node['file_path'])
            )
        
        for edge in self.edges:
            cursor.execute(
                'INSERT OR REPLACE INTO code_graph_edges VALUES (?, ?, ?)',
                (edge['source_id'], edge['target_id'], edge['type'])
            )
        
        conn.commit()
        conn.close()
        
        print(f"数据库已生成：{db_path}")
        print(f"  节点数：{len(self.nodes)}")
        print(f"  边数：{len(self.edges)}")

    def run(self):
        """执行生成"""
        print("=" * 60)
        print("CodeGraph 代码图谱生成器")
        print("=" * 60)
        
        self.scan_java_files()
        self.generate_sqlite_db()
        
        print("\n✅ 生成完成")


def main():
    parser = argparse.ArgumentParser(description='CodeGraph 代码图谱生成器')
    parser.add_argument('-r', '--project-root', default='.', help='项目根目录（默认：当前目录）')
    parser.add_argument('-o', '--output', default='./data/codegraph', help='输出路径（默认：./data/codegraph）')
    parser.add_argument('-p', '--packages', default='', help='扫描的包名（逗号分隔，默认：扫描所有）')
    
    args = parser.parse_args()
    
    scan_packages = [p.strip() for p in args.packages.split(',') if p.strip()] if args.packages else None
    
    generator = CodeGraphGenerator(args.project_root, args.output, scan_packages)
    generator.run()


if __name__ == '__main__':
    main()
