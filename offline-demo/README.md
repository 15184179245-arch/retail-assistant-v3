# 零售门店助手 V3 · 可运行的核心代码


## 下载后立即打开

1. 在仓库点击 **Code → Download ZIP**，并解压。
2. 双击解压目录中的 **index.html**。Mac 和 Windows 都可用普通浏览器打开，不用安装、联网或登录。
3. 网页直接打开时显示用真实 V3 代码预先生成的合成结果。想让每次点击都现场运行，请用下面的本地模式。

### 运行真实 V3 核心

需要 Python 3，无需 `pip install`。打开终端（Mac）或 PowerShell（Windows），进入解压目录：

- **Mac**：`python3 run_demo.py`
- **Windows**：`py -3 run_demo.py`；如果系统只识别 `python`，则运行 `python run_demo.py`。

脚本会自动打开浏览器。若未自动打开，复制终端显示的本机地址。在页面点击「重新运行」会重新执行 V3 数据查询和价签生成。按 **Ctrl+C** 停止。没有 Python 3 也可以使用上面的免安装预览。

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
