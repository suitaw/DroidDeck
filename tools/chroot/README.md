# chroot 容器

手机上的固定位置：`/data/local/chroot/enter`（来自 `enter`）和 `/data/local/chroot/debian`（Debian rootfs，`usr/local/lib/cr-relay.py` 来自这里，需要 python3）。
App 的 `chroot-shell` 只会运行 `enter user <App uid>` 或确认后运行 `enter root`。挂载都在临时命名空间里，退出就消失。
