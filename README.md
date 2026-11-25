# 零售门店助手 V3 · 可运行的核心代码

> **目录同步中：** 先同步已脱敏的项目目录。`.gitkeep` 是占位文件，表示该目录的源码尚未上传。文中的运行说明仅对应已经发布的版本。

## 30 秒看懂

**一个把找货、确认库存和打印价签接在一起的门店工具。** 输入商品规格，程序返回货位与库存；选定标签尺寸，实际排版引擎会生成打印指令。

| 做到的事 | 对门店有什么用 |
| --- | --- |
| 按名称和规格找商品、查库位 | 不用翻货架和旧单据 |
| 生成热敏价签 | 标签窄了会调整文字，避免打印出界 |
| 用模拟库存离线运行 | 不需要原门店账号，也不会碰真实库存 |

## 源码与运行说明


这是 V3 的脱敏源码快照，保留原项目已实现的库位管理、查询策略和标签排版。新增离线入口，以及替换后的合成测试样本。它不连接真实门店、不向打印机发送指令。

- **库位管理**：原 SQLite 库位树、商品与货位绑定、库存读取。运行时只用内存数据库，不导入任何旧记录。
- **商品查询**：原规格归一、两路查询汇合、无售价筛选等逻辑和对应回归测试。
- **热敏标签**：原 Python 排版及 TSPL 编码，原 Kotlin 排版及 TSPL／CPCL 编码。源码保留文字换行、防越界和不同幅面适配。示例的价签指令由实际引擎生成，输入全是合成数据。

## 本地体验

需要 Python 3.10 以上，无需安装第三方依赖。在仓库根目录运行：

```sh
PYTHONPATH=src python3 portfolio.py
PYTHONPATH=src python3 portfolio.py --tspl
```

第一条输出库位树、查询结果、排版指令；第二条只输出可阅读的模拟价签指令文本。仓库的 `examples/synthetic-label.tspl` 是这一命令的示例输出。

## 测试

```sh
for test in src/print/test_price_tag_layout.py src/search/test_spec_rules.py \
  src/search/test_dual_merge.py src/search/test_unpriced.py src/utils/test_validate.py; do
  PYTHONPATH=src python3 "$test" || exit
done
```

导出后通过了 66 项 Python 测试和离线入口检查。Kotlin 模块以 Kotlin 1.9.22、Java 17 编译，并通过 38 项 JUnit 测试；可用 Gradle 在 `android-core` 中执行 `gradle test`。

## 不在展示版中的部分

不含历史提交、公司旧数据、客户端预装数据库、远端和证书、内部接口、账号配置、日志和工作交接。完整 Android APK、生产网关、设备发现／打印传输、业务系统同步、聊天和语音服务不在这个离线版本中。
