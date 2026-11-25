# 家居重建：组员与 Agent 部署入口

更新：2026-11-17。**首次部署使用现成 APK + Windows 电脑服务，无需编译 Android/Rust。** 本文是当前部署依据，其他按日期命名文档和子目录 README 多为历史实验，不要照搬旧参数和作者本机路径。

当前功能版为 **v0.14.0**，安装包在 `dist/mobile-functions-v014/ChairPreview.apk`。新增扫码连接、连接检测、电脑任务历史与下载恢复、房间布局保存、恢复、重命名与删除、整组旋转及模型引用保护。小米 17 Pro 已通过启动、扫码配对、电脑任务历史和已完成模型打开检查。房间布局放置待真机验收。详见 [功能版说明](docs/功能版-v014.md)。

2026-11-13 已清理旧版发布目录和重复编译中间文件；保留当前 v0.13.1、组员部署包、必要校准输入及主要验证记录。历史文档里部分旧 APK/截图路径因此不再存在，不能再作为可用下载入口。删除明细与保留文件校验见 `docs/历史版本清理-20261113-010418-127.json`。

流程：Android 扫描家具 → Wi-Fi 上传照片与相机记录 → 电脑 COLMAP 优化几何/相机、Brush 训练 3D Gaussian Splatting → 手机下载场景 → 人工裁剪、确认实测尺寸 → AR 多家具摆放。也保留手机训练入口。输出是高斯模型，不是 OBJ/GLB 网格。

## 发给组员什么

发送 `dist/组员部署包/` 中**最新生成的整个 ZIP**。旁边有相同内容的未压缩文件夹，可直接复制。不要只发本文或 APK。

包内有 APK、电脑服务及依赖清单、完整 COLMAP/Brush Windows 工具、自检脚本、协议测试；附 Android Java/Rust 适配源码供阅读。**这是可部署运行包，不是包含完整 Android/Rust 工具链和历史素材的源码构建包。**

不打包个人 `server-data/`、连接码/令牌、扫描数据、训练结果、签名私钥、Python 环境或手机备份。APK 内仍包含开发时的椅子演示模型及参考采集素材，演示不代表新扫描已成功。不要直接压缩作者整个工作目录。

在原始源码工作目录可运行 `python deploy/package_handoff.py` 重新打包：使用文件允许清单，创建新目录并记录逐文件 SHA256，不覆盖旧包。

## 给接手 Agent 的执行要求

1. 读取本文及包内 `release.json`、`package-manifest.json`，确认 Windows x64 和文件完整。不要照搬旧 IP、`D:/Python`、手机序列号或账户。
2. 创建 `.venv`、安装固定依赖，运行部署自检与协议测试。
3. 启动服务，确认真实 WLAN/以太网 IPv4 和 `/api/health`；告知用户实际地址。连接码让用户从本机页面查看，不写进聊天或交付文档；新版可在 App 点“扫码连接电脑”，扫描电脑页面的二维码。
4. 安装现有 APK、准备 ARCore。已有同包名应用先尝试覆盖安装；签名冲突先说明并由用户决定，**不能擅自卸载/清空数据**。
5. 让用户拍摄和测量家具，完成真实上传→训练→下载→查看→裁剪→尺寸确认→AR 验收。物理移动手机、识别地面、点击放置需用户完成。
6. 记录真实设备、版本、耗时、步数、模型校验和及失败信息。没有真机/GPU 就报告未验证，不把单测或帮助命令当成端到端成功。

本路线不需要付费生成 API、OpenAI/Tripo/Meshy 账户、token 额度、CUDA Toolkit、PyTorch、Unity 或 Docker。训练由本机 COLMAP + Brush 自动执行，正常运行不需要聊天 Agent 在线。首次安装 Python 依赖、手机准备 ARCore 可能需要外网；扫描数据通过局域网传输。

## 1. 环境

| 部分 | 要求与已验证范围 |
| --- | --- |
| 电脑 | Windows x64，支持 Vulkan 且驱动正常的 GPU；已验证 RTX 4060 Laptop 8GB，其他显卡需实测 |
| Python | 推荐 Python 3.13 x64；NumPy 2.2.3、SciPy 1.15.2、Pillow 11.1.0、qrcode 8.2 |
| 磁盘 | 工具解压约 500MB，另需 Python 环境和照片/训练中间文件；为任务预留数 GB，档案会累积 |
| 手机 | ARM64 Android、OpenGL ES 3.0、兼容的 Google Play Services for AR（ARCore）、相机权限；真机验证为小米 17 Pro |
| 深度遮挡 | 额外要求 ARCore Depth 支持；普通 AR 可用不代表深度可用 |
| 网络 | 手机和电脑同一可互访局域网；访客/校园 Wi-Fi 隔离、VPN 可能阻断连接 |

清单最低 Android 7 不代表所有 Android 7+ 手机都支持扫描/AR/训练。手机训练还要求兼容 Vulkan 驱动；本次先走“电脑生成”，不用安装旧诊断程序“家具重建验证”。

## 2. 安装电脑依赖

**完整解压 ZIP** 到本地可写目录，如 `C:\Projects\Furniture`，在根目录打开 PowerShell：

```powershell
py -3.13 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r requirements-server.txt
& .\.venv\Scripts\python.exe deploy/check_deployment.py
& .\.venv\Scripts\python.exe -m unittest discover -s desktop-server -p test_server.py -v
```

没有 `py` 时，用已安装 Python 3.13 的完整路径执行第一条。不需要激活虚拟环境，不沿用原作者 D 盘路径。安装依赖需要访问 Python 包仓库。

自检应没有 FAIL。协议测试使用临时目录和模拟计算器，不读取真实任务，不证明 GPU 训练或手机 AR 可用。

## 3. 启动服务和确定 IP

双击 **`启动电脑重建服务.cmd`**，优先使用本目录 `.venv`，其次系统 `py`/`python`。终端保持运行，本机浏览器显示当前地址及新生成的 **8 位连接码**。也可前台启动：

```powershell
& .\.venv\Scripts\python.exe desktop-server/server.py --open
```

多个网卡/自动识别不对时，查看带默认网关的 WLAN/以太网实际 IPv4：

```powershell
Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | Format-List InterfaceAlias,IPv4Address
```

再指定地址启动，下面 IP **仅为示例，必须替换**：

```powershell
& .\.venv\Scripts\python.exe desktop-server/server.py --host 192.168.1.23 --port 8268 --open
Invoke-RestMethod http://192.168.1.23:8268/api/health
```

健康接口应返回 `protocol: 1`，空闲时 `busy: false`。手机填 `http://电脑实际IPv4:8268`；不能填 `127.0.0.1`、`0.0.0.0`、虚拟网卡地址，也不要照搬旧的 `192.168.3.218` / `172.16.4.21`。

Windows 防火墙询问时，允许所用 Python 在可信专用网络通信。本机能访问、手机不能时，再查防火墙/路由器隔离；不要关闭整个防火墙。必要时由管理员为该 Python 添加仅专用网络、本地子网、TCP 8268 入站规则。

同一地址重复启动只打开已有页面。换 Wi-Fi 后停止旧服务、用新 IP 重启，在手机重新连接。训练期间电脑不休眠、不退出服务；无任务时在前台 Ctrl+C 停止。关闭浏览器不会停止服务。正常使用不需要 USB，开发安装时可选用数据线。

## 4. 手机安装与完整操作

1. 将 `app/家居重建-v0.14.0.apk` 发到手机并安装，SHA256 在 `release.json`。如用 ADB：`adb -s <实际设备序列号> install -r app/家居重建-v0.14.0.apk`。普通用户直接安装即可，无需 USB 调试。
2. 打开“家居重建”，授予相机权限，按系统提示准备 ARCore。无法运行 ARCore 的手机不能当作扫描/AR 支持设备。
3. “扫描家具 → 进入扫描”，等待定位，再“开始采集”。缓慢绕拍约 30–60 秒，包含侧后面及高低视角，家具不动、有纹理、光照稳定，避免变焦和运动模糊；最后“结束并保存”。服务要求 20–300 张完整照片和对应记录。
4. “生成模型”选扫描记录→“电脑生成”→“连接 / 更换电脑”，输入电脑当前完整地址与 8 位连接码。连接页面 `/` 只供电脑本机查看，手机访问它返回 403 是预期行为；手机浏览器可用 `/api/health` 检查连通性。
5. “上传扫描并在电脑生成”，等待上传、处理、下载。可返回后从“生成模型 → 连接电脑 / 查看电脑处理任务”继续查询。不要因暂时无进度反复提交。
6. “查看三维模型并裁剪”，调整范围框并保存家具副本，背景不会自动全部消失。
7. 输入实际测量高度，必要时输入宽深、检查接地，再“放进房间”。缓慢移动手机识别地面、点击地面放置；可选其他模型继续放置、逐件选中和锁定，工具区可折叠。

**这个入口接收 App 保存的照片、内参和 ARCore 位姿，不接收随意几张商家图片、无位姿 ZIP 或任意视频。** 内置椅子展示不代表新家具重建通过。

完成多家具摆放后，在房间工具中点“保存房间布局”；首页“已保存房间布局”可恢复、改名和删除。恢复须重新点击地面指定参考位置，可整组旋转调整朝向，不是自动识别旧房间。

## 5. 默认参数、耗时与边界

电脑使用逐帧 PINHOLE 内参、CPU SIFT 相邻/闭环匹配、COLMAP 三角化与 BA，将几何和相机共同对齐回 ARCore 米制坐标；Brush 0.3.0 GPU 训练 7000 步、SH2、长边上限 1280、最多 250000 高斯。手机训练为 2400 步、长边 768、最多 24000 高斯。

处理预算十分钟，上传/下载另计。电脑接近预算会尝试交付校验通过的最新检查点，实际可能少于 7000 步；没有有效模型则报失败，不保证所有设备和素材十分钟出结果。退出清理也可能影响实际墙钟耗时。电脑同一时间处理一个任务。

已有一次 113 张 640×480 衣架扫描在本机完成 7000 步、44493 高斯，处理约 151 秒，只是单次实测。仍可能模糊、缺失背面、残留背景；尺寸须实测，遮挡边缘受设备深度分辨率限制。

提供 APK 为 v0.14.0（versionCode 23、包名 `com.furniture.chairdemo`），包含尺寸确认→接地副本→AR 入口修复及尺寸换算后的协方差数值稳定处理。已生成安装包；模拟器功能回归不能代替完整真机放置回归，新设备应重新实测。

## 6. 验收、日志和排错

- 自检无 FAIL，服务协议测试通过。
- 电脑及手机均能访问实际 IP 的 `/api/health`，App 配对成功。
- 用户新扫描实际上传，任务 completed，记录真实耗时/步数/高斯数/SHA256，手机完成下载与三维显示。
- 家具裁剪与尺寸确认后，用户实际移动手机识别地面、点击放置，检查卷尺尺度、接地和支持设备上的遮挡。仅启动 AR 会话不算放置成功。

| 现象 | 排查 |
| --- | --- |
| Python/numpy 缺失 | 检查根目录 `.venv`，用它的 python 安装依赖，不混用环境 |
| 重建工具缺失、DLL 缺失 | 必须保留完整 `tools/colmap/`（含 bin、plugins）及 `tools/brush/`，不是只复制 exe；核对包哈希 |
| GPU/Vulkan 错误 | 检查显卡厂商驱动及独显可用性；帮助命令通过不代表训练可用 |
| 连接拒绝/超时 | 查运行状态、当前 IP、同网络互访、防火墙、VPN、客户端隔离；仅有手机流量不够 |
| 连接码错误/401 | 查看这台电脑的当前页面重新配对，不复制作者的 connection.json |
| 端口占用 | 查具体进程，仅处理本项目服务或换端口；勿强杀无关进程 |
| 扫描零帧/一直定位 | 检查权限、ARCore、光线、纹理，用户拿起手机缓慢移动 |
| 有效视角少/漂移 | 增加清晰度、视角重叠、完整绕拍，减少运动物体，重新扫描 |
| 重启后原任务失败 | 未完成任务标记中断，已完成结果保留；从 App 重试，不保证训练迭代续跑 |
| 模型进不了 AR | 先检查裁剪及尺寸确认，保留原数据并记录完整错误、设备与版本，检查接地副本生成是否失败 |
| APK 覆盖失败 | 查签名、版本及手机安装提示；不要擅自卸载已有 App，私有模型会丢失 |

电脑数据在 `server-data/jobs/<job_id>/`，含 status.json、照片、COLMAP/训练日志及 model.ply。`server-data/connection.json` 是本机凭据，不对外发送。服务日志显示在启动终端。不要未经用户授权清理数据；手机删除本地模型不等于删除电脑任务档案。

当前为配对鉴权的局域网 HTTP 原型，未实现公网 HTTPS、多用户账号与配额。不要开放公网端口，也不需要云服务器/域名/第三方生成 API。

## 代码入口与后续开发

| 位置 | 内容 |
| --- | --- |
| desktop-server/server.py | 配对、上传、查询、取消、下载和持久任务 |
| desktop-server/pipeline.py | 数据检查、COLMAP、Brush、时间预算和模型校验 |
| android-app/src/ | 原生 Java/OpenGL ES/ARCore，扫描、三维查看、裁剪、AR、网络客户端 |
| android-app/validate_capture.py | 照片/相机记录数据合同 |
| mobile-reconstruction/native/ | 手机 Brush/JNI 适配源码 |
| requirements-server.txt、deploy/ | 依赖、自检、交付打包 |
| tools/colmap/、tools/brush/ | 配套 Windows 工具 |

不要为首次部署运行旧 `android-app/build.py --with-training`：其源码构建依赖 SDK build-tools 36.0.0、android-37.0、JDK、ARCore 1.48.0 AAR、预编译原生库、参考采集与历史校准文件，还含原作者路径。**本部署包复用 APK，不声称可以从零重编 Rust 或完全复现 APK**。需要修改并发布 App 时应另外整理构建输入和签名策略；原签名私钥未提供。

旧根 README 在源码工作目录 `docs/历史README-2026-11-03.md`（旧链接按原根目录理解）。历史视频抽帧路线、训练对照和旧 UI 文档不作为首次部署步骤。

## 第三方来源

配套工具为 COLMAP 4.2.1 Windows nocuda、Brush 0.3.0 Windows；App 使用 ARCore 1.48.0 和 Brush Android 适配。保留随附文件，参见包内 THIRD_PARTY.md。

- [COLMAP 4.2.1](https://github.com/colmap/colmap/releases/tag/4.2.1)
- [Brush v0.3.0](https://github.com/ArthurBrussee/brush/releases/tag/v0.3.0)
- [ARCore Android SDK](https://github.com/google-ar/arcore-android-sdk)

本文根据本地代码与已有验证记录整理，来源链接本轮未重新核查在线版本，版本以随包哈希为准。
