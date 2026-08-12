#!/bin/bash
# 测试 generate-codegraph.py 脚本

set -e

# 创建临时项目
TEMP_DIR=$(mktemp -d)
mkdir -p "$TEMP_DIR/src/main/java/com/example/service"
mkdir -p "$TEMP_DIR/src/main/java/com/example/controller"

# 创建示例 Java 文件
cat > "$TEMP_DIR/src/main/java/com/example/service/UserService.java" << 'JAVAEOF'
package com.example.service;

/**
 * 用户服务
 */
public interface UserService {
    /**
     * 获取用户
     */
    User getUser(Long id);
}
JAVAEOF

cat > "$TEMP_DIR/src/main/java/com/example/controller/UserController.java" << 'JAVAEOF'
package com.example.controller;

import com.example.service.UserService;

/**
 * 用户控制器
 */
public class UserController {
    private UserService userService;
    
    public User getUser(Long id) {
        return userService.getUser(id);
    }
}
JAVAEOF

# 运行脚本
python3 snap-agent-spring-boot-2x-starter/src/main/resources/scripts/generate-codegraph.py \
    -r "$TEMP_DIR" \
    -o "$TEMP_DIR/output/codegraph" \
    -p "com.example"

# 验证结果
if [ -f "$TEMP_DIR/output/codegraph.db" ]; then
    echo "✅ 数据库文件已生成"
    
    NODE_COUNT=$(sqlite3 "$TEMP_DIR/output/codegraph.db" "SELECT COUNT(*) FROM code_graph_nodes;")
    EDGE_COUNT=$(sqlite3 "$TEMP_DIR/output/codegraph.db" "SELECT COUNT(*) FROM code_graph_edges;")
    
    echo "节点数：$NODE_COUNT"
    echo "边数：$EDGE_COUNT"
    
    if [ "$NODE_COUNT" -gt 0 ]; then
        echo "✅ 测试通过"
    else
        echo "❌ 测试失败：没有节点"
        exit 1
    fi
else
    echo "❌ 测试失败：数据库文件未生成"
    exit 1
fi

# 清理
rm -rf "$TEMP_DIR"
