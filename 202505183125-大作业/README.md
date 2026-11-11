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
2. **账单浏览**：可按账户 / 类型 / 类别关键字 / 备注关键字 / 日期区间筛选（全部可回车跳过），
   结果按日期排序、每页 10 条**真分页**（n/p 翻页、f/l 首页末页、g 跳页、q 返回），每页显示小计；
3. **手动记账**：新增账单，逐字段校验（id 唯一性、日期、账户、类型、金额非负且最多两位小数），
   非法输入循环重输，程序不崩溃；
4. **统计总览**：去重后的收入合计、支出合计、结余（BigDecimal 精确计算），并输出
   **按月收支表**、**支出类别汇总（含占比）**、**各账户余额**；看完可选择导出 CSV 报表；
5. **数据持久化**：退出时保存到 `bills.dat.txt`（UTF-8 文本），再次启动自动加载并与导入数据按 id 合并去重；
   保存采用**临时文件 + 原子替换 + 自动 .bak 备份 + 回读校验**，加载时对损坏行逐行容错跳过并计数；
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

自检（改完代码跑一遍，确认没有回归）：

```bash
cd out
java -cp accountbook.jar com.accountbook.SelfTest    # 51 项断言，全通过退出码 0
```

### 一键启动（Windows / Linux / macOS 各一份，功能对等）

| 系统 | 脚本 | 用法 |
|---|---|---|
| Windows | `out/start.bat` | 双击，或在 cmd 里执行 `start.bat` |
| Linux | `out/start.sh` | `chmod +x start.sh` 后 `./start.sh`（也可以 `bash start.sh`） |
| macOS | `out/start.sh` | 同上（打开终端 `cd` 到该目录后 `./start.sh`） |

两个脚本做的事完全一样，都是**先切到脚本所在目录再启动**，所以从任意路径调用都不会
"找不到 config.properties"：

1. 检查 `java` 是否存在、主版本是否 ≥ 11，不满足给中文提示并退出（不进入程序半死不活）；
2. 检查 `accountbook.jar` / `config.properties` / 数据 csv 是否在同一目录，缺哪个报哪个；
3. 处理编码：Windows 用 GBK 输出（cmd 默认代码页 936），Linux/macOS 用 UTF-8；
   Linux 若 locale 不是 UTF-8（如服务器 `LANG=C`）会自动兜底切到 `C.UTF-8`；
4. 无图形环境（纯 SSH）时提示"菜单 9 不可用"，避免误以为程序坏了。

> `start.bat` 是 Windows 批处理，**在 Linux 下不能执行**（换行符与语法都不兼容），
> 所以单独提供了 `start.sh`；两者都是薄壳，真正逻辑在 `accountbook.jar` 里。

手动运行（不依赖脚本，任何系统都一样）：

```bash
java -jar accountbook.jar
```

> 注意：配置文件与数据文件用的是**相对路径**，手动运行时请先 `cd` 到 jar 所在目录，
> 否则程序读不到 `config.properties`。

## 四、类的设计

```
com.accountbook
├── Main              # 菜单循环、输入校验、EOF 安全退出（保存后退出）
├── Bill              # 账单实体（不可变对象，BigDecimal 金额）
├── DateUtil          # 三种日期格式解析（STRICT 严格校验）
├── CsvImporter       # CSV 导入 + 清洗 + 变体B去重（保留靠后）
├── ImportResult      # 导入报告（总数/逐行丢弃原因/去重原因）
├── AccountService    # 变体B：账户余额、转账、退出一致性校验
├── Storage           # 持久化（原子写入+备份+回读校验；加载容错跳过损坏行）
├── Config            # 读取 config.properties（学号/水印/路径/天气城市）
├── TextIO            # 文本读取：UTF-8/GBK 自动识别（防"记事本另存为 ANSI"整份乱码）
├── FontSetup         # 图形界面字体适配：自动选可显示中文的字体（防 Linux/WSL 窗口全方块）
├── WeatherService    # 加分项9.1：桥接教师 WeatherKit（反射调用 + 缓存 + 异常降级）
├── BillQuery         # 账单筛选条件（控制台与 GUI 共用，避免两套逻辑不一致）
├── Exporter          # CSV 报表导出（UTF-8 带 BOM，Excel 直接可读；字段按 RFC4180 转义）
├── SelfTest          # 零依赖自检（51 项断言，改完代码跑一遍防回归）
└── AccountBookFrame  # 加分项9.2：Swing 图形界面（复用同一套业务类）

src/WeatherKit.java   # 教师提供的天气工具类（默认包；本项目就地补了 3 处，见第七章与 9.1）
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
   关闭后再从 `bills.dat.txt` 重新加载数据，保证两种界面数据一致；
9. **天气接口一直返回 404 / 403**（加分项险些卡死）：教师代码写死的是
   `https://geoapi.qweather.com/v2/city/lookup`，和风天气已停用公共域名（2026-01/06 分批停服），
   实测 geoapi 404、devapi 与 api 均返回 `403 Invalid Host`，且 API Host 本身参与鉴权，
   猜不出来。解决：教师类只把 KEY / GEO_API / NOW_API 三个常量改为"可由配置注入"（默认值不变），
   其余一字未动；`WeatherService` 负责把 `config.properties` 的 host 拼成两个完整地址注入，
   失败时把 403/404/401/429 分别翻译成"该去填 host 还是 key"。
10. **记事本另存为 ANSI 之后整份数据变乱码**：Windows 记事本保存 .csv/.properties 默认用
   GBK（ANSI），程序按 UTF-8 硬读，于是「北京」变「鍖椾含」——城市查不到、类型"收入/支出"
   匹配不上，整份 CSV 全被判成脏数据，用户只会看到"57 行全被丢弃"。解决：新增 `TextIO`，
   先探测编码（UTF-8 BOM → UTF-8 → 否则回退 GBK）再读，并在控制台明确提示
   "检测到不是 UTF-8，已按 GBK 读取"。配置文件与数据文件同样走这条路。
11. **EOF（Ctrl+D / 输入重定向）在日期输入处死循环**：`readDate` 原本把 `readLine()` 写在
   `try` 里并 `catch (Exception e)`，结果 Ctrl+D 抛出的 `EofSignal` 被当成"日期格式错误"吞掉，
   循环继续读、继续吞，控制台疯狂刷"日期非法"。解决：`readLine()` 移到 `try` 之外，
   `catch` 收窄为 `DateTimeParseException`，让 EOF 正常冒泡到主循环（保存后退出）。
12. **"保存成功"可能是假的**：直接用 `Files.write` 覆盖时，写到一半出错会留下半截文件，
    而程序已经打印了"已保存"。解决：先写 `.tmp` → 备份 `.bak` → 原子替换 → **回读文件数行数**
    与内存条数比对，不一致就明确报错；加载时单行损坏只跳过该行（并告知第几行、什么原因），
    不让一个坏字符毁掉整份数据。

13. **Linux 下图形界面中文全变方框**：控制台中文正常、窗口里全是 □，一开始容易误判成编码问题。
   实际是 Swing 只能用系统中已安装的字体，而精简 Linux / WSL 里没有中文字体、终端却用自己的字体
   所以显示正常。解决：新增 `FontSetup`，按候选表（雅黑/苹方/Noto/思源/文泉驿）查找并用
   `canDisplay('中')` 验证字形，写入 UIManager 保留原字号；找不到就打印各发行版的安装命令。
   排查顺带发现一个会崩的隐患：`DISPLAY` 设了但 X 连不上时 `isHeadless()` 返回 false，
   建窗抛 `AWTError`（Error 接不住）会让菜单 9 带崩整个程序，已改为先真实探测再决定。

## 六、跨平台说明

**任务书第 5 条四项要求，逐条对应：**

| 任务书要求 | 本项目的做法 |
|---|---|
| ① 交付可执行 JAR，`java -jar` 直接运行，不依赖 IDE | `out/accountbook.jar`，Manifest 已写 `Main-Class: com.accountbook.Main`；零第三方依赖，只用 JDK 标准库 |
| ② Windows 或 Linux 两种系统各一张运行截图，需包含"关于"界面（水印可见） | **Windows**：`screenshots/windows.png`（cmd 内运行 start.bat，菜单 2/4/5/7/8，末段含"关于"页与水印 `67c7b1fe`）；**Linux**：`screenshots/linux.png`（全流程）、`screenshots/linux-about.png`（"关于"页，水印清晰）；另有 `gui.png` / `gui-transfer.png`（图形界面） |
| ③ 两个系统中文都不乱码（数据文件是 UTF-8，Windows 控制台默认不是） | 见下方"三层编码策略" |
| ④ 数据/配置文件用相对路径或程序内可配置路径，禁止写死盘符 | `config.properties` 里只有文件名（`dataFile=202505183125.csv`、`storeFile=bills.dat.txt`），没有 `C:\` 这类盘符；启动脚本先 `cd` 到自己所在目录 |

**三层编码策略（对应要求③）：**

1. **控制台输出**：`Main.makeOut()` 按系统选编码——Windows 用 GBK（cmd 代码页 936），
   Linux/macOS 用 UTF-8；也可用 `-Dstdout.encoding=xxx` 手动覆盖，`start.sh` 已显式传 UTF-8；
2. **读取文件**：`TextIO` 先看 UTF-8 BOM，再严格按 UTF-8 解码，失败回退 GBK，
   所以记事本"另存为 ANSI"的文件也能正常导入，并会在控制台提示"已按 GBK 读取"；
3. **导出 CSV**：写 UTF-8 **BOM**，Excel 双击直接正确显示中文。

**两个启动脚本的差异：**

| | `start.bat`（Windows） | `start.sh`（Linux/macOS） |
|---|---|---|
| 切目录 | `cd /d %~dp0` | `cd "$(dirname "$0")"` |
| 编码 | 依赖 `Main.makeOut()` 选 GBK | 显式 `-Dstdout.encoding=UTF-8` + locale 兜底 |
| 版本检查 | `where java` + 启动失败提示 | `java -version` 解析主版本，< 11 直接中文报错 |
| 图形环境 | 默认有 | `DISPLAY` 为空时提示菜单 9 不可用 |

> 说明：Windows 截图是在 cmd 里真实运行 `start.bat` 抓的屏；Linux 截图是在无图形桌面的
> 服务器上，用 Xvfb 虚拟显示器 + xterm 真实运行 `./start.sh` 后抓屏得到的。
> 两张图里菜单 8（关于）的输出与水印 `67c7b1fe` 都是程序真实打印的内容，未做任何后期合成。

### 图形界面中文显示成方框（□）怎么办

**现象**：控制台里中文正常，但菜单 9 打开的窗口里所有中文都变成方框，数字和英文正常。

**原因**：Swing 用的是系统的字体，Java 自己不带中文字形。精简安装的 Linux、WSL、
Docker 镜像里常常一个中文字体都没有；而终端能显示中文，是因为终端用的是它自己的字体，
跟 Java 无关——所以"控制台正常、窗口方块"是典型症状，很容易误判成程序编码问题。

**三步定位**（程序里带了诊断命令，不用另装工具）：

```bash
fc-list :lang=zh | head -5                 # 系统装了哪些中文字体
java -jar accountbook.jar --fonts          # Java 实际能看到哪些
```

| 系统字体 | Java 可见 | 结论 | 怎么处理 |
|---|---|---|---|
| 有 | 有 | 正常 | 程序会自动选中最合适的中文字体（如 Noto Sans CJK SC） |
| 有 | **无** | Java 没接上系统字体服务 | 装完整 JDK（不要用 `-headless` 包），并确认 `libfontconfig1` 已安装 |
| **无** | 无 | 系统真没装中文字体 | `sudo apt install -y fonts-noto-cjk && fc-cache -fv`（CentOS：`yum install google-noto-sans-cjk-fonts`） |
| 无 | 无，且 `--fonts` 报 `AWTError` | 根本没有图形环境 | 在带桌面的环境运行，或先修好 `DISPLAY` |

**程序侧做了什么**（`FontSetup`，新增类）：

1. 启动窗口前按候选表找中文字体：Windows 雅黑 → macOS 苹方 → Noto Sans CJK → 思源 → 文泉驿 → 宋体/黑体；
2. **不只看名字，还用 `canDisplay('中')` 验证字形真的存在**，名字对但缺字形的情况是有的；
3. 候选都没命中就扫描系统全部字体，取第一个真能显示中文的（兜底国产发行版）；
4. 把选中的字体写入 UIManager 所有 `*.font` 键，**保留各控件原有字号与粗细**（统一字号会把界面比例弄乱）；
5. 一个都找不到时，控制台直接打印上面那张表里的安装命令——而不是让用户对着一屏方框猜。

也可以手动指定字体（现场演示时换字体很方便）：

```bash
java -Dapp.font="Noto Sans CJK SC" -jar accountbook.jar
```

> 顺带修掉一个会崩溃的隐患：原来 `show()` 只用 `isHeadless()` 判断环境，而 `DISPLAY`
> 设了但 X 服务器连不上时它返回 false，随后创建窗口会抛 `AWTError`（Error，`catch Exception`
> 接不住），菜单 9 会直接把整个程序带崩、内存数据都来不及保存。现在先做一次真实探测，
> 连不上就退回控制台并提示，数据照常保存。

## 七、目录结构

```
202505183125-大作业/
├── src/
│   ├── WeatherKit.java         # 教师提供（默认包；经教师同意补了 3 处，diff 见根目录）
│   └── com/accountbook/        # 15 个类（含编码识别、字体适配、筛选、导出、自检、天气、图形界面）
├── WeatherKit-改动对照.txt     # 教师类的完整 diff（答辩时可直接 diff 自证）
├── out/
│   ├── accountbook.jar         # 可执行 JAR（含 WeatherKit.class）
│   ├── config.properties       # 运行所需配置（weather.city/host/key）
│   ├── 202505183125.csv        # 教师下发的数据
│   ├── start.bat               # Windows 一键启动
│   └── start.sh                # Linux / macOS 一键启动（chmod +x 后 ./start.sh）
├── screenshots/                # windows.png（Windows 关于页，含水印）/ linux.png（Linux 全流程）
│                               # linux-about.png（Linux 关于页）/ gui.png / gui-transfer.png
└── README.md
```

## 八、加分项实现

### 9.1 线上天气接口（+5）

**合规说明**：任务书要求"必须使用教师提供的类，不得自己重写网络请求代码"。
经教师确认**允许修改教师类**，但"不得自己重写网络请求"这条底线必须守住。本项目的做法：

- 整份工程里**只有 `src/WeatherKit.java` 出现 `java.net` / `HttpClient`**
  （`grep -rl "java.net\|HttpClient" src/` 只命中它一个文件），学生自己的 15 个类里
  **没有一行网络代码**——`WeatherService` 只 import 了反射相关的类，做的是
  "配置注入 / 反射调用 / 异常转文案 / 10 分钟缓存"这四件事；
- 对教师类的修改**只有 3 处，且都在原方法内部就地修改**，没有把代码搬出去另写一套客户端，
  也没有另起一个 HTTP 工具类。完整 `diff` 见项目根目录 `WeatherKit-改动对照.txt`。

**3 处改动及原因**（都是"让老师的类能在今天的接口上跑通"，不是改设计）：

| # | 改动 | 原因 |
|---|---|---|
| 1 | `KEY` / `GEO_API` / `NOW_API` 三个常量改为可由配置注入，**默认值仍是教师原值** | 和风天气 2025-04 起用"专属 API Host"取代公共域名，旧地址已彻底不可用 |
| 2 | `get()` 按字节取回并判断 `Content-Encoding`，新增纯解压方法 `gunzip` | 服务端强制 gzip，而 Java 11 的 `BodyHandlers.ofString` **不会解压** |
| 3 | `now()` 入口加 `city` 空值校验；`checkCode()` 补上 403 的中文说明 | 空城市名会抛空指针、403 只会显示裸错误码，都不友好 |

**未改动**：请求 URL 拼装、超时（5s 连接 / 8s 请求）、重定向策略、`pick()` 正则解析、
`checkCode` 的 200/401/402/404/429 语义、`ApiException` 类型、
`now(String)` 的签名与返回格式。方法数 11 → 12（只多了 `gunzip`）。

> **为什么这 3 处必须改（实测证据，2026-11）**
>
> | 请求地址 | 实测返回 |
> |---|---|
> | `geoapi.qweather.com/v2/city/lookup`（教师原值） | **404**，域名已下线 |
> | `devapi.qweather.com/geo/v2/city/lookup` | 403 `{"title":"Invalid Host"}` |
> | `api.qweather.com/geo/v2/city/lookup` | 403 `Invalid Host` |
> | `<你的专属Host>/geo/v2/city/lookup` | 假 Key 时 403，真 Key 时 200 |
>
> 官方公告：`devapi` 2026-01-01 停服，`api` / `geoapi` 2026-06-01 停服，
> 且 **API Host 本身参与鉴权**（随机分配、猜不出来），所以旧地址无论 Key 对不对都查不到。
>
> 第二层坑更隐蔽：和风天气的响应是 **gzip 压缩**的，而 Java 客户端**根本不发**
> `Accept-Encoding`（抓包实测请求头只有 `Host` / `User-Agent` / `Upgrade`），服务端照样压缩；
> Java 11 的 `BodyHandlers.ofString` 不解压，把 gzip 字节按 UTF-8 硬解成一串
> `?n?0?W?reM?q?`，于是 `checkCode` 找不到 `"code"` 字段，报"接口返回格式异常"。
> 本项目用本地 gzip 服务器复现了这条链路，补上解压后同一份响应正确解析出
> `{"code":"200",…}`，最终输出 `北京 晴 3°C 湿度30% 东南风1级`（已验证，见下）。

**集成方式**（完全按任务书要求）：

- **调用位置**：菜单 4「统计总览」打印 `【实时天气 · 统计页】…`；菜单 3「手动记账」成功后
  打印同一条作为备注参考；图形界面顶栏常驻显示，右侧有【刷新天气】按钮；
- **城市名从配置读取**：`config.properties` 的 `weather.city`，代码里不硬编码城市；
- **`ApiException` 妥善处理**：断网 / 400 / 401 / 402 / 403 / 404 / 429 逐条翻译成
  "发生了什么 + 下一步怎么做"的中文提示，程序不崩、记账流程不中断；
- **未配置时不发无意义请求**：没填 Host 或 Key 时 0.06 秒内直接提示缺什么、去哪儿填，
  不会干等 8 秒超时；
- **缓存 10 分钟**：避免频繁调用触发限流；图形界面用 `SwingWorker` 后台查询，断网不卡窗；
- **默认包反射调用**：`WeatherKit` 在默认包，Java 规范禁止命名包 import 默认包类型，
  故用 `Class.forName("WeatherKit")` 调 `now(String)`——教师文件不需要加 package 声明。

**配置（30 秒，改完不用重新编译）**：

1. 登录 [和风天气控制台](https://console.qweather.com) → 设置 Settings → **API Host**，
   复制形如 `abcd1234.re.qweatherapi.com` 的域名；
2. 项目管理 → 创建项目 → 创建凭据 → 选 **API Key**，复制 Key；
3. 打开 `out/config.properties` 填两行：`weather.host=…`、`weather.key=…`；
4. 重新运行 `java -jar out/accountbook.jar`，菜单 4 即显示真实天气。

**已经在本地 mock 服务器上验证过的完整链路**（gzip + JSON + 格式化）：

```
【实时天气 · 统计页】北京 晴 3°C 湿度30% 东南风1级
```

即：请求 → gzip 响应 → 解压 → `checkCode` 通过 → 正则取值 → 按教师原格式输出，全程正确。

### 9.2 自制图形界面（+8）

- 技术选型：**Swing**（零第三方依赖，JDK 自带，符合“无外部依赖”要求）；
- 入口：控制台菜单 **9. 图形界面**，关闭窗口后自动回到控制台并重新加载数据，
  **控制台功能完整保留**；
- 可用功能（全部为真实业务，不是空壳）：
  - 账单表格（点表头可排序，显示 id/日期/账户/类型/类别/金额/备注/固定支出；
    金额列右对齐并按国内习惯**收入红色、支出绿色**）；
    **排序按真实类型**而不是按文本——`getColumnClass` 声明了 `Integer / LocalDate / BigDecimal`，
    否则 `TableRowSorter` 会退化成字符串比较，`9.00` 排在 `10000.00` 后面、`999` 排在 `1000` 后面；
  - **搜索与筛选条**：关键字（备注/类别/账户模糊搜索）+ 账户 + 类型，输入即筛选，
    右侧实时显示"显示 N / 共 M 条"，与控制台共用 `BillQuery`；
  - 顶部统计卡片（收入 / 支出 / 结余，**跟随筛选结果**刷新，不是永远显示全量）；
  - 「导入 CSV」：复用 `CsvImporter`，弹窗显示 总行数 / 丢弃 / 去重 / 本次有效 / 保留原有 / 合计
    （与已有数据**按 id 合并**，不会把刚手动记的账冲掉，合并后同步刷新一致性校验基线）；
  - 「记一笔」：表单对话框，id 唯一性、金额、日期、类别非空全部校验；
  - 「转账」：选择转出/转入账户与金额，**生成两笔记录**，余额即时刷新；
  - 「一致性校验」：弹窗显示四账户 `基线 / 本次变动 / 合计 [通过]`；
  - 「导出 CSV」：把**当前筛选结果**导出为"账单明细 + 统计汇总"两个文件；
  - 「保存」：写入 `bills.dat.txt`；关闭窗口时自动保存；
  - 实时天气：后台线程查询，失败不影响界面操作；
  - 底部状态栏：账户余额汇总 + 学号 + 水印。
- 复用而非复制：GUI 直接调用 `CsvImporter / AccountService / Storage / WeatherService`，
  与控制台共用同一套业务代码，因此两边算出的 50 条 / 16995.00 / 6567.16 完全一致；
- 无图形环境（headless）时给出提示并返回控制台，不会因 `HeadlessException` 崩溃。

## 九、工程质量：零依赖自检

改完代码怎么确认"没有把别处改坏"？项目里带了一个自检程序（不使用 JUnit，纯 JDK）：

```bash
cd out
java -cp accountbook.jar com.accountbook.SelfTest
```

实测输出：

```
===== 自检开始 =====
[通过] 日期 2026-01-05
...
[通过] 导出文件以 BOM 开头（Excel 不乱码）
===== 自检结束：通过 51 项，失败 0 项 =====
```

覆盖的 16 组规则：

| 组 | 覆盖内容 |
|---|---|
| 1 | 三种日期格式解析 + 非法日期（2026-13-40、平年 2-29）必须被拒 |
| 2 | 金额校验：负数 / 千分位 / 三位小数 / 非数字 全部拒绝 |
| 3 | 真实 CSV 导入结果：57 行 → 丢 5 → 去重 2 → 有效 50 |
| 4 | 变体B去重方向：id=1005 保留 1312.00、id=1031 保留 155.28 |
| 5 | 统计口径：16995.00 / 6567.16 / 10427.84 与四账户余额 |
| 6 | 按月汇总与总账自洽（月度收入合计 = 总收入） |
| 7 | 转账生成两笔、转出账户减少、总资产不变 |
| 8 | 一致性校验：未改动通过，删一条必须报警 |
| 9 | 持久化：存-读条数/内容一致、自动生成 .bak |
| 10 | 筛选：账户+类型、关键字、日期区间、起止填反自动纠正 |
| 11 | 导出：行数正确、文件以 BOM 开头（Excel 不乱码） |
| 12 | 编码兼容：**GBK 保存的 CSV** 仍能导入且中文不乱码（临时夹具，跑完即删） |
| 13 | 表头识别：**没有表头**的 CSV，第一行不能被当表头吃掉 |
| 14 | 数据文件损坏行容错：非法类型 / 负金额 / 非法日期 / 列数不对 → 跳过并计数 |
| 15 | 金额上限：超上限拒绝，边界值仍可用 |
| 16 | 导入合并：先手动记账再导入，原有记录仍然在（不会被冲掉） |

> 全部断言使用**教师下发的真实 CSV**，没有为了通过测试而生成任何假数据；
> 只有"转账/篡改"这类必须构造对象的用例才在内存里临时 new 两条记录，
> 以及第 12~14 组需要验证异常输入时临时写的夹具文件（**均在 `finally` 里删除，
> 不写入 `bills.dat.txt`、不参与任何交付数据**）。
