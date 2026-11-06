# 个人记账本（控制台 + 图形界面）— JavaSE 基础大作业

- 学号：202505183125
- 个人变体：**变体 B（多账户）**
- 水印：67c7b1fe（见 config.properties，程序内不硬编码）
- 运行环境：JDK 11+
- 加分项：**9.1 线上天气接口（+5）**、**9.2 自制图形界面（+8）**

## 一、功能清单

### 基础功能（7 项）
1. **导入数据**：读取教师下发的 `202505183125.csv`，UTF-8 编码读取，按清洗规则导入，
   显示总行数、有效行数、丢弃行数，并逐行列出丢弃原因；
2. **账单浏览**：按日期排序，每页 10 条真分页（n/p 翻页，q 返回）；
3. **手动记账**：新增账单，逐字段校验（id 唯一性、日期、账户、类型、金额非负且最多两位小数），
   非法输入循环重输，程序不崩溃；
4. **统计总览**：去重后的收入合计、支出合计、结余（BigDecimal 精确计算）；
5. **数据持久化**：退出时保存到 `bills.dat.txt`（文本格式），再次启动自动加载并与导入数据按 id 合并去重；
6. **配置文件**：`config.properties` 存放学号、水印、数据文件路径、持久化路径，程序启动读取；
7. **关于**：显示程序名称、作者学号、水印。

### 个人变体 B（多账户）
- i. 按账户（微信/支付宝/校园卡/银行卡）分别统计余额（收入 − 支出）；
- ii. 账户间转账：**生成两笔对应记录**（转出一笔支出 + 转入一笔收入），不直接修改余额；
- iii. 退出前一致性校验：启动时对持久化数据做初始余额快照，退出时验证
  `各账户当前余额 = 初始余额 + 本次会话新增账单收支合计`，不一致时醒目提示。

## 二、数据清洗规则（与任务书 4.2 对应）

| 规则 | 处理 |
|---|---|
| 列数 ≠ 8 | 丢弃（千分位逗号会把金额切成两列，也属此类） |
| 金额非非负数字（负数/非数字） | 丢弃 |
| 日期无法按三种格式解析 | 丢弃（STRICT 严格模式，2026-13-40 拒绝） |
| 类型不是"收入/支出" | 丢弃 |
| id 非数字或超出 int 范围 | 丢弃（防御性规则） |
| **变体 B 去重**：id 相同 | **保留文件中出现顺序靠后的一条** |
| 空行 | 静默跳过（不计总行数、不算丢弃） |

**导入结果（本人数据标准答案）**：总行数 57 → 脏数据丢弃 5 行（id 1051/1052/1053/1054/1055）
→ 去重删除 2 行（id 1005、1031）→ **有效账单 50 条**。

去重后统计：收入 16995.00 / 支出 6567.16 / 结余 10427.84。
账户余额：微信 3379.43 / 支付宝 9118.37 / 校园卡 −2924.57 / 银行卡 854.61。

## 三、怎么编译运行

```bash
# 编译（JDK 11+，注意 WeatherKit.java 在 src 根目录、属于默认包，必须一起编译）
javac --release 11 -encoding UTF-8 -d out/classes src/WeatherKit.java src/com/accountbook/*.java

# 打包可执行 JAR（-C out/classes . 会把默认包的 WeatherKit.class 一并打进 JAR 根目录）
jar cfe out/accountbook.jar com.accountbook.Main -C out/classes .
```

运行前把 `accountbook.jar`、`config.properties`、`202505183125.csv` 放同一目录：

```bash
java -jar accountbook.jar
```

Windows 下可双击 `start.bat`（自动处理编码）。若手动在 cmd 运行且中文乱码，
可执行 `chcp 65001` 后重试；Linux/macOS 默认 UTF-8 无需处理。

## 四、类的设计

```
com.accountbook
├── Main              # 菜单循环、输入校验、EOF 安全退出（保存后退出）
├── Bill              # 账单实体（不可变对象，BigDecimal 金额）
├── DateUtil          # 三种日期格式解析（STRICT 严格校验）
├── CsvImporter       # CSV 导入 + 清洗 + 变体B去重（保留靠后）
├── ImportResult      # 导入报告（总数/逐行丢弃原因/去重原因）
├── AccountService    # 变体B：账户余额、转账、退出一致性校验
├── Storage           # 持久化（文本格式保存/加载/合并）
├── Config            # 读取 config.properties（学号/水印/路径/天气城市）
├── WeatherService    # 加分项9.1：桥接教师 WeatherKit（反射调用 + 缓存 + 异常降级）
└── AccountBookFrame  # 加分项9.2：Swing 图形界面（复用同一套业务类）

src/WeatherKit.java   # 教师提供的天气工具类（默认包，未做任何修改）
```

数据流转：CSV → CsvImporter（清洗/去重）→ List<Bill> → AccountService（统计/校验）
→ Storage（持久化）→ 下次启动 merge 合并。
图形界面（AccountBookFrame）直接复用上述业务类，不复制逻辑，两种界面数字必然一致。

## 五、遇到的问题和解决过程

1. **中文乱码（Windows）**：程序最初强制 UTF-8 输出，但 Windows cmd 默认代码页是
   GBK(936)，导致乱码。解决：控制台编码自适应——Windows 下输出 GBK、其他系统 UTF-8，
   并支持 `-Dstdout.encoding=` 启动参数手动指定；
2. **非法日期被解析成功**：`2026-13-40` 用宽松模式会被"进位"成 2027-02-09，导致清洗错误。
   解决：`DateTimeFormatter` 全部使用 `ResolverStyle.STRICT`；
3. **去重方向陷阱**：数据中重复 id 的两行，"（改）"标记和金额大小方向都不一致，
   不能凭直觉猜，必须严格按变体 B 规则"保留文件出现顺序靠后"实现；
4. **金额精度**：double 累加有浮点误差，统计与标准答案对不上，改用 BigDecimal；
5. **EOF 死循环**：输入流关闭时 Scanner.nextLine 抛异常被捕获后循环重试导致死循环，
   改为统一 readLine() 检测 EOF，先保存数据再安全退出；
6. **Excel 打开 CSV 乱码**：Excel 双击默认 GBK 解码 UTF-8 文件会乱码，
   属查看工具问题，正确方式是"数据 → 从文本/CSV → 编码选 65001"；
7. **教师 WeatherKit 在默认包，无法直接 import**：`WeatherKit.java` 没有 package 声明，
   而本项目在 `com.accountbook` 包下，编译报 "cannot find symbol"。
   解决：不改教师源码，用 `Class.forName("WeatherKit")` + `Method.invoke` 反射调用，
   并统一捕获 `ApiException`，断网/限流时只提示不崩溃；
8. **图形界面阻塞控制台**：Swing 窗口是独立事件线程，控制台不能直接"等它关"。
   解决：`invokeAndWait` 建窗 + `CountDownLatch` 等待 `windowClosed`，
   关闭后再从 `bills.dat.txt` 重新加载数据，保证两种界面数据一致。

## 六、跨平台说明

- 全部使用相对路径，无写死盘符；
- 数据/配置/持久化文件均为 UTF-8；
- Windows、Linux 双系统运行截图见 `screenshots/` 目录。

## 七、目录结构

```
202505183125-大作业/
├── src/
│   ├── WeatherKit.java         # 教师提供（默认包，未修改）
│   └── com/accountbook/        # 10 个类（含天气服务与图形界面）
├── out/
│   ├── accountbook.jar         # 可执行 JAR（含 WeatherKit.class）
│   ├── config.properties       # 运行所需配置（含 weather.city）
│   ├── 202505183125.csv        # 教师下发的数据
│   └── start.bat               # Windows 一键启动
├── screenshots/                # windows.png / linux.png / gui.png
└── README.md
```

## 八、加分项实现

### 9.1 线上天气接口（+5）

- **只用教师提供的类**：`src/WeatherKit.java` 与教师下发文件**字节级一致**（MD5 相同），
  没有重写任何网络请求代码；
- **调用位置**：菜单 4「统计总览」打印 `【实时天气 · 统计页】…`，
  菜单 3「手动记账」成功后打印同一条信息，作为本次记账的备注参考；
- **城市名从配置读取**：`config.properties` 的 `weather.city`（默认北京），代码中不硬编码城市；
- **异常处理**：WeatherKit 抛出的 `WeatherKit.ApiException`（断网 / 限流 429 / Key 无效 401）
  在 `WeatherService` 里统一捕获，转成一行友好提示（如“查询失败：请求过于频繁（429），
  请稍后再试（不影响记账功能）”），**程序绝不崩溃、记账流程不中断**；
- **工程细节**：结果缓存 10 分钟避免频繁调用触发限流；图形界面里用 `SwingWorker`
  后台查询，断网时窗口不会卡死。

> **关于 JSON 解析**：教师类内部已用正则提取字段，本项目不再重复解析。
> **关于默认包**：`WeatherKit` 位于默认包，Java 规范禁止命名包直接 import 默认包类型，
> 因此 `WeatherService` 用反射调用其 `public static String now(String)`，
> 既保持教师文件不被修改，也能正常使用（答辩可重点解释这一点）。

**使用前需填 API Key**：打开 `src/WeatherKit.java` 第 32 行，把占位符换成自己的
[和风天气](https://console.qweather.com) 免费 Key，再重新编译打包即可看到
“北京 晴 3°C 湿度30% 东南风1级”这样的真实天气。未填 Key 时程序会显示
“查询失败：…（不影响记账功能）”，功能不受影响。

### 9.2 自制图形界面（+8）

- 技术选型：**Swing**（零第三方依赖，JDK 自带，符合“无外部依赖”要求）；
- 入口：控制台菜单 **9. 图形界面**，关闭窗口后自动回到控制台并重新加载数据，
  **控制台功能完整保留**；
- 可用功能（全部为真实业务，不是空壳）：
  - 账单表格（点表头可排序，显示 id/日期/账户/类型/类别/金额/备注/固定支出）；
  - 顶部统计卡片（收入 / 支出 / 结余，实时刷新）；
  - 「导入 CSV」：复用 `CsvImporter`，弹窗显示 总行数 / 丢弃 / 去重 / 有效条数；
  - 「记一笔」：表单对话框，id 唯一性、金额、日期、类别非空全部校验；
  - 「转账」：选择转出/转入账户与金额，**生成两笔记录**，余额即时刷新；
  - 「一致性校验」：弹窗显示四账户 `基线 / 本次变动 / 合计 [通过]`；
  - 「保存」：写入 `bills.dat.txt`；关闭窗口时自动保存；
  - 实时天气：后台线程查询，失败不影响界面操作；
  - 底部状态栏：账户余额汇总 + 学号 + 水印。
- 复用而非复制：GUI 直接调用 `CsvImporter / AccountService / Storage / WeatherService`，
  与控制台共用同一套业务代码，因此两边算出的 50 条 / 16995.00 / 6567.16 完全一致；
- 无图形环境（headless）时给出提示并返回控制台，不会因 `HeadlessException` 崩溃。
