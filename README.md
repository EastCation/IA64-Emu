# IA64-Emu

一个类似 [Vectras VM](https://github.com/AnBui2004/Vectras-VM-Emu-Android) 的 Android 虚拟机应用，专门运行
[syunnPC/qemu-system-ia64](https://github.com/syunnPC/qemu-system-ia64)（实验性的 QEMU IA-64 全系统模拟器）。

应用本身负责虚拟机管理、启动参数生成、QEMU 进程生命周期、串口日志和 VNC 画面；QEMU 与 IA-64 EFI 固件通过
“引导包（bootstrap）”提供，由 GitHub Actions 构建，可在应用内下载或从本地 ZIP 导入。

> IA-64 目标仍处于实验阶段。TCG 模拟非常慢，桌面级客户机需要性能较好的 64 位设备。

## 功能

- VM 列表：新建、编辑、删除、启动、停止、打开画面
- 完整 IA-64 机型选择
  - `hp-i2000`、`hp-zx2000`、`hp-zx6000`、`hp-rx2660`
  - `itanium-vpc`、`itanium2-vpc`、`itanium2-vpc-iommu`
- CPU 型号、内存、vCPU、ATI / Quadro2 显卡、分辨率
- 两个硬盘 + 一个 CD/DVD 镜像；`qcow2` / `raw` / `vmdk` / `vdi` / `vhdx` 自动识别
- 可在应用内创建空白稀疏磁盘（raw），无需外部工具
- 可手动选择存储总线（Auto / IDE-SATA / SCSI），只显示当前机型支持的总线
- 运行中可通过 QMP 更换或弹出 CD/DVD 介质，并可通过 HMP Monitor 执行 QEMU 命令
- 每台 VM 独立的 EFI NVRAM（保存在 VM 目录，可重置）
- 用户模式网络（`e1000` / `e1000e` / `rtl8139`）
- 串口控制台与 QEMU 日志实时查看
- 内置 VNC 客户端（Raw / CopyRect / DesktopSize），支持：
  - 单指点击 = 左键，长按 = 右键，双指上下滑动 = 滚轮
  - 物理键盘输入、屏幕上的 Esc/Ctrl/Alt/Del/F1-F12 等按键
  - 手机输入法直通（IME）：点击工具栏“Keyboard/输入法”即可用系统 QWERTY/中文输入法
    直接输入客户机；仅提交完成的文本，不会把拼音组合串发送给客户机
  - 文本输入、Ctrl+Alt+Del、自动重连
- 实时 QEMU HMP Monitor（串口页的 Monitor 标签），可执行 `info status`、`info block`、
  `change cdrom0 ...` 等命令
- 可选“向局域网开放 VNC”，并在应用中显示局域网 VNC 地址
- 可选“连接到外部 VNC 地址”，用内置客户端连接其他设备上的 VNC 服务
- 引导包下载进度、若存在 `.sha256` 则自动校验、本地 ZIP 导入、PRoot 自动部署

## 架构

```
┌────────────────────────────── App (Kotlin) ──────────────────────────────┐
│  VmListFragment / VmEditFragment / VmDisplayFragment / SerialFragment    │
│        │                    │                         │                  │
│        ▼                    ▼                         ▼                  │
│   VmStore (JSON)      QemuArgs builder          RfbClient (VNC)          │
│        │                    │                                            │
│        └──────────► QemuService (foreground service)                     │
│                             │                                            │
│                             ▼                                            │
│            PRoot (filesDir/proot/<abi>/proot)                            │
│                             │  ptrace, 无需系统 execve 权限                │
│                             ▼                                            │
│   filesDir/bootstrap/rootfs  ├── /usr/local/bin/qemu-system-ia64          │
│                              ├── /usr/local/share/qemu/ia64-firmware.bin  │
│                              ├── glibc 与依赖库                          │
│                              └── VNC 127.0.0.1:590x / 串口 / QMP         │
└──────────────────────────────────────────────────────────────────────────┘
```

### 为什么 targetSdk 是 28？

Android 10（API 29）开始禁止应用 `execve()` 应用私有目录中的文件。本项目使用 PRoot 从
`filesDir/proot/...` 启动。PRoot 自己再通过 `ptrace` 加载客户机 ELF，从而绕过对 QEMU 二进制的 exec 限制。
因此，应用必须使用 `targetSdk <= 28` 才能直接执行 PRoot（Vectras VM 也是同样的做法）。

这意味着：

- 应用适合通过 APK 侧载或自建渠道分发，**不能直接上架 Google Play**（Play 要求更高的 targetSdk）。
- 可以继续在现代 Android（10–16）上安装运行。
- 旧版外部存储行为被保留，QEMU 可以直接按路径打开 `/sdcard` 上的磁盘镜像。
- 安装 PRoot 时会把 `assets/proot/<abi>/` 复制到 `filesDir/proot/<abi>/`，并设置可执行位。

如果你必须提高 targetSdk，需要把 PRoot 打包为 `libproot.so` 放进 `jniLibs`，从 `nativeLibraryDir`
执行，或增加一个 JNI exec helper；否则启动会失败。

## 构建 App

要求：

- JDK 21（Temurin 或 Android Studio 自带 JBR）
- Android SDK：compileSdk 36 / build-tools 36
- 网络可访问 Google Maven 与 Maven Central

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接使用 GitHub Actions 的 `Build APK` 工作流，产物在 Actions artifacts 中。

注意：本项目已移除 `foojay-resolver` 与 `gradle/gradle-daemon-jvm.properties`，避免 Gradle 在部分
JDK 上错误地下载/启动工具链。使用非标准 JDK 时请显式设置 `JAVA_HOME`。

## 构建 QEMU 引导包

引导包包含：`qemu-system-ia64`、`qemu-img`、IA-64 EFI 固件、QEMU 数据文件（VGA BIOS / keymaps）、
glibc 及其依赖库。构建在 GitHub Actions 的 Fedora 容器中完成，使用原生 arm64 / x86_64 runner。

1. Fork 本仓库并启用 Actions。
2. 运行 **Build QEMU bootstrap** 工作流（`workflow_dispatch`），或推送 `v*` 标签。
3. 工作流会：
   - 在 `ubuntu-24.04` 的 Fedora 容器中用 `ia64-linux-gnu-gcc` 构建 `ia64-firmware.bin`
   - 在 `ubuntu-24.04-arm`（arm64）和 `ubuntu-24.04`（x86_64）上分别构建 headless QEMU
     （`--disable-gtk --disable-sdl --enable-vnc --enable-slirp`）
   - 运行 `scripts/make_bootstrap.sh` 打包为：
     - `bootstrap-arm64-v8a.zip`
     - `bootstrap-x86_64.zip`
     - 对应的 `.sha256`
   - 上传 artifacts；推送标签时自动发布到 Release
4. 在应用 **设置 → QEMU 引导包** 中：
   - 输入 ZIP 直链（例如 Release 资产的 `https://github.com/.../releases/download/.../bootstrap-arm64-v8a.zip`）
     并下载；或
   - 选择“从本地 ZIP 安装”，导入构建产物。

### 本地/自建 CI 构建

只要环境与目标架构一致（在 arm64 机器上打 arm64 包，在 x86_64 机器上打 x86_64 包），可以复用同一脚本：

```bash
sudo dnf install -y bison flex gcc gcc-c++ glib2-devel pixman-devel libslirp-devel \
  ninja-build python3-pip zip unzip which file findutils diffutils perl gettext
git clone --depth 1 https://github.com/syunnPC/qemu-system-ia64.git qemu-src
mkdir build && cd build
../qemu-src/configure --target-list=ia64-softmmu \
  --prefix=/usr/local --datadir=/usr/local/share/qemu \
  --disable-gtk --disable-sdl --disable-opengl --disable-docs --disable-werror \
  --enable-vnc --enable-slirp
ninja -j"$(nproc)" qemu-system-ia64 qemu-img
cd ..
# firmware 需要 ia64-linux-gnu 工具链，或从 CI 的 ia64-firmware artifact 获取
scripts/make_bootstrap.sh build arm64-v8a aarch64 out qemu-src firmware-dir
```

`scripts/make_bootstrap.sh` 会：

- 以 `ldd` 收集 QEMU 的全部动态依赖，按原始绝对路径复制到 rootfs
- 复制 ELF 解释器（`/lib64/ld-linux-x86-64.so.2` 或 `/lib/ld-linux-aarch64.so.1`）
- 复制固件与 `pc-bios` 数据到 `/usr/local/share/qemu`
- 写入 `manifest.json`、`/etc/resolv.conf`、`/etc/passwd`、`/tmp` 等
- 生成 ZIP 和 SHA-256

### 引导包格式

```text
manifest.json
rootfs/
  usr/local/bin/qemu-system-ia64
  usr/local/bin/qemu-img
  usr/local/share/qemu/ia64-firmware.bin
  usr/local/share/qemu/vgabios-ati.bin
  usr/local/share/qemu/keymaps/...
  lib64/ld-linux-x86-64.so.2     # 或 lib/ld-linux-aarch64.so.1
  lib64/*.so*                    # 或 /lib、/usr/lib64
  etc/{passwd,hosts,resolv.conf,ld.so.cache}
  root/ tmp/ dev/ proc/ sys/
```

`manifest.json`：

```json
{
  "format": 1,
  "abi": "arm64-v8a",
  "qemuVersion": "11.0.0",
  "builtAt": "2026-01-01T00:00:00Z",
  "entry": "/usr/local/bin/qemu-system-ia64",
  "datadir": "/usr/local/share/qemu",
  "firmware": "/usr/local/share/qemu/ia64-firmware.bin"
}
```

应用会校验 `format`、`abi`、`entry` 与 `firmware` 是否存在，并通过 PRoot 以如下形式启动：

```bash
proot --kill-on-exit --link2symlink -0 -r rootfs \
  -b /dev -b /proc -b /sys -b /storage -b /sdcard \
  -b <filesDir>:/app -b <vmDir>:/vm -b <filesDir>/tmp:/tmp \
  -w /root /usr/local/bin/qemu-system-ia64 \
  -machine <machine>,nvram=/vm/nvram \
  -m 2G -smp 1 -vga ati -display vnc=127.0.0.1:0 ...
```

QMP 控制套接字为 `/vm/qmp.sock`，串口输出写入 `/vm/serial.log`，QEMU 标准输出写入 `/vm/qemu.log`。

## 使用

1. 安装引导包（设置页或首页提示卡片）。
2. 点击“新建虚拟机”，选择机型与 CPU，分配内存。
3. 添加磁盘镜像（`qcow2`/`raw`）和安装 ISO。
4. 保存并启动，应用会自动打开 VNC 画面。
5. 首次启动会进入 IA-64 EFI 固件；可从 CD 启动安装客户机系统。
6. 桌面系统建议在 VM 设置中关闭串口控制台（保持默认 VGA），需要安装日志时可打开串口控制台并查看串口页。

### 文件访问

- 应用 targetSdk 28，默认保留旧版外部存储访问；首次启动请求存储权限。
- Android 11+ 如仍无法读取 `/sdcard`，可在设置中授予“所有文件访问权限”。
- 通过文件选择器选中的文件，如果能解析为真实路径（如 `/storage/emulated/0/...`），会直接使用；
  否则会复制到 `filesDir/vms/<id>/imports/` 后使用。
- VM 目录结构：

```text
filesDir/vms/<vmId>/
  config.json
  nvram
  serial.log
  qemu.log
  qmp.sock / monitor.sock（运行时）
  imports/
```

## 常见问题

- **提示“尚未安装 QEMU 引导包”**：先构建/下载引导包并在设置里安装。
- **启动后立即报错**：打开“串口 → QEMU 日志”查看。常见原因：磁盘路径不可读、内存超过设备可用内存、
  CPU 与机型不匹配（例如 zx2000 只支持 1 个 CPU/mckinley-900）。
- **VNC 黑屏**：确认显卡为 ATI/Quadro2；首次启动 EFI 可能需要几十秒；也可打开串口查看固件输出。
- **启动时报 `Could not open '.../etc/qemu/qemu.conf': Function not implemented`**：这是 PRoot 对缺失目录
  返回 ENOSYS 导致的。官方引导包已经包含 `/usr/local/etc/qemu/qemu.conf`；自定义引导包请确保创建该目录。
- **运行中更换 CD/DVD**：显示页顶部菜单的 Media，或 Monitor 里执行
  `change cdrom0 /path/to/image.iso`；弹出用 `eject cdrom0`。
- **“PRoot 文件不完整”**：设置页点“重装 PRoot”。如果设备不是 arm64-v8a / x86_64，则无法运行。
- **网络不通**：仅支持 QEMU user-mode 网络（`-nic user`），客户机内使用 DHCP；不支持桥接/Wi-Fi 直通。
- **中文输入**：当前通过物理键盘或屏幕按键发送 X11 keysym；未实现 Android IME 组合输入。
- **VNC 安全**：局域网开放模式没有密码，请只在可信网络使用。

## 已知限制

- 仅支持 64 位 ARM（arm64-v8a）与 x86_64 设备。
- IA-64 指令/设备模拟仍不完整，部分系统无法启动或运行不稳定。
- 无 KVM/硬件加速；纯 TCG 软件模拟，速度取决于 CPU 单核性能。
- 无音频、无 3D 加速、无 USB 直通、无共享文件夹。
- 内置 VNC 客户端未实现 VNC 密码认证（QEMU 默认无密码）。
- Monitor 是 HMP（文本命令）；QMP 只用于应用内部的介质热插拔和停止。
- IME 的中文等非 ASCII 字符以 X11 Unicode keysym 发送，是否生效取决于客户机/固件。
- 由于 targetSdk 28，应用不符合 Google Play 当前目标 API 要求。

## 许可证与声明

- QEMU 及本 IA-64 分支：GNU GPL v2，源码见
  <https://github.com/syunnPC/qemu-system-ia64>。
- PRoot / libtalloc：GNU GPL v2，源码见 <https://github.com/termux/proot> 与 Termux packages；
  `app/src/main/assets/proot/` 下包含 COPYING 与 NOTICE。
- 应用不包含任何客户机操作系统、安装介质或商业固件；请自行合法获取并遵守其许可。
- 本项目与 Intel、HPE、QEMU Project、Microsoft 及 Vectras VM 官方均无隶属关系。
