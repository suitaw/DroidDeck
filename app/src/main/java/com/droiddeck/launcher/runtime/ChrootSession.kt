package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Experimental: run the desktop session in a real chroot (through su) instead of proot.
 *
 * It takes the proot command SessionService has already built, keeps its bind list and guest command, and turns
 * them into a root launch script: a private mount namespace, the same binds, chroot into the rootfs, drop to the
 * app's uid and groups, switch to the app's SELinux domain, then exec the same guest command.
 * The SELinux switch matters: a memfd made in the su domain cannot be passed to the compositor (wl_shm fails).
 */
object ChrootSession {
    private const val TAG = "ChrootSession"
    private const val SU = "/system/bin/su"

    @Volatile var active = false
        private set

    @Volatile private var rootOk = false

    // File(SU).exists() is not usable: the app's SELinux domain may not stat su, yet it can run it. Ask su itself.
    private fun rootWorks(): Boolean = try {
        val p = ProcessBuilder(SU, "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && out == "0"
    } catch (e: Exception) { false }

    fun enabled(context: Context): Boolean {
        if (!SessionPrefs.chrootMode(context)) return false
        if (rootOk) return true
        rootOk = rootWorks()
        if (!rootOk) Log.w(TAG, "chroot mode is on but su is not usable; staying on proot")
        return rootOk
    }

    private class Parsed(val root: String, val cwd: String, val binds: List<String>, val guest: List<String>)

    private fun parse(cmd: List<String>): Parsed? {
        var root: String? = null
        var cwd = "/"
        val binds = ArrayList<String>()
        var i = 1
        loop@ while (i < cmd.size) {
            val a = cmd[i]
            when {
                a == "-b" -> { binds += cmd.getOrNull(i + 1) ?: return null; i += 2 }
                a == "-r" -> { root = cmd.getOrNull(i + 1) ?: return null; i += 2 }
                a == "-w" -> { cwd = cmd.getOrNull(i + 1) ?: return null; i += 2 }
                a == "-i" -> i += 2
                a == "-0" -> i += 1
                a.startsWith("--") -> i += 1
                else -> break@loop
            }
        }
        if (root == null || i >= cmd.size) return null
        return Parsed(root, cwd, binds, cmd.subList(i, cmd.size))
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun selfContext(): String = try {
        File("/proc/self/attr/current").readText().trim { it == '\u0000' || it.isWhitespace() }
    } catch (e: Exception) { "" }

    private fun selfGroups(): String = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(",") ?: ""
    } catch (e: Exception) { "" }

    /** The su command that runs [prootCommand] in a chroot, or null if it cannot be turned into one. */
    fun command(context: Context, prootCommand: List<String>): List<String>? = try {
        build(context, prootCommand)
    } catch (e: Exception) {
        Log.e(TAG, "could not prepare the chroot; staying on proot", e)
        null
    }

    private fun write(file: File, text: String) {
        file.delete() // an earlier root-owned copy can be removed, not overwritten
        file.writeText(text)
    }

    private fun build(context: Context, prootCommand: List<String>): List<String>? {
        val parsed = parse(prootCommand)
        if (parsed == null) {
            Log.w(TAG, "cannot read the proot command; staying on proot")
            return null
        }
        val uid = android.os.Process.myUid()
        val files = context.filesDir.path
        val dir = File(context.filesDir, "chroot").apply { mkdirs() }
        val dom = File(dir, "dom.sh")
        // The rootfs needs real hard links here (libICE locks, for one) and the app's domain may not make them.
        // Terminal ioctls on the app's own ptys are refused too, so nothing in Konsole sees a tty. TIOCSTI (0x5412) stays out.
        write(File(dir, "sepolicy.rule"), "allow untrusted_app_27 app_data_file file link\n" +
            "allow untrusted_app_27 untrusted_app_all_devpts chr_file { read write getattr open ioctl }\n" +
            "allowxperm untrusted_app_27 untrusted_app_all_devpts chr_file ioctl { 0x5400-0x5411 0x5413-0x54ff }\n")
        write(File(dir, "linkprobe.sh"), "export PATH=/usr/bin:/bin; f=/tmp/.dd-link.\$\$; : > \$f; ln \$f \$f.2; r=\$?; rm -f \$f \$f.2; [ \$r -eq 0 ] || exit \$r; [ ! -x /usr/bin/script ] || script -qec 'test -t 0' /dev/null </dev/null >/dev/null 2>&1\n")
        write(dom,
            "#!/bin/sh\n" +
                "cd \"\${DD_CWD:-/}\" 2>/dev/null || cd /\n" +
                "[ -z \"\$DD_CTX\" ] || printf '%s' \"\$DD_CTX\" > /proc/self/attr/current 2>/dev/null || echo 'chroot: could not switch the SELinux domain' >&2\n" +
                "unset DD_CTX DD_CWD\n" +
                "exec \"\$@\"\n",
        )
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        line("#!/system/bin/sh")
        line("# generated by DroidDeck chroot mode at each launch; do not edit")
        line("if [ -z \"\$DD_NS\" ]; then DD_NS=1 exec /system/bin/unshare -m /system/bin/sh \"\$0\" \"\$@\"; fi")
        line("unset LD_LIBRARY_PATH LD_PRELOAD PROOT_LOADER PROOT_TMP_DIR PROOT_NO_SECCOMP")
        line("mount --make-rprivate /")
        line("R=${q(parsed.root)}")
        line("F=${q(files)}")
        line("exec >\"\$F/chroot/launch.log\" 2>&1; chmod 644 \"\$F/chroot/launch.log\"")
        line("b() {")
        line("  [ -e \"\$1\" ] || return 0")
        line("  if [ -d \"\$1\" ]; then")
        line("    mkdir -p \"\$R\$2\" 2>/dev/null")
        line("    mount -o bind \"\$1\" \"\$R\$2\" 2>/dev/null || echo \"bind fail \$1 -> \$2\"")
        line("  else")
        line("    mkdir -p \"\$(dirname \"\$R\$2\")\" 2>/dev/null; [ -e \"\$R\$2\" ] || { : > \"\$R\$2\"; } 2>/dev/null")
        line("    chroot \"\$R\" /usr/bin/mount --bind \"\$1\" \"\$2\" 2>/dev/null || echo \"bind fail \$1 -> \$2\"")
        line("  fi")
        line("}")
        for (spec in parsed.binds) {
            val src = spec.substringBefore(':')
            val dst = if (spec.contains(':')) spec.substringAfter(':') else spec
            if (src.startsWith("/proc/self/")) continue
            if (src == "/dev" || src == "/proc" || src == "/sys") {
                line("mount --rbind ${q(src)} \"\$R\"${q(dst)} 2>/dev/null || echo \"rbind fail ${src}\"")
            } else {
                line("b ${q(src)} ${q(dst)}")
            }
        }
        // proot's --link2symlink rootfs: the multi-call coreutils cannot find its own name through the l2s links.
        // Real hard links to the binary, bound over the directory, make it work without touching the rootfs.
        line("C=\"\$R/usr/lib/cargo/bin/coreutils\"; O=\"\$F/.chroot-cu\"")
        line("if [ -L \"\$C/.l2s.env0001\" ]; then")
        line("  REAL=\$(readlink -f \"\$C/.l2s.env0001\")")
        line("  if [ ! -f \"\$O/env\" ] || ! [ \"\$O/env\" -ef \"\$REAL\" ]; then")
        line("    rm -rf \"\$O\"; mkdir -p \"\$O\"")
        line("    for f in \"\$C\"/*; do ln \"\$REAL\" \"\$O/\${f##*/}\" 2>/dev/null; done")
        line("    chown -R $uid:$uid \"\$O\"")
        line("  fi")
        line("  b \"\$O\" /usr/lib/cargo/bin/coreutils")
        line("fi")
        // apt runs under proot, which turns hard links into .l2s symlinks that programs named by argv[0] cannot follow.
        line("if [ \"\$R/var/lib/dpkg/status\" -nt \"\$F/chroot/l2s-stamp\" ]; then")
        line("  chroot \"\$R\" /usr/bin/find /usr /etc /opt -xdev -type l -lname '*/.l2s.*' 2>/dev/null | while read -r l; do")
        line("    real=\$(chroot \"\$R\" /usr/bin/readlink -f \"\$l\")")
        line("    [ -f \"\$real\" ] || continue")
        line("    ln \"\$real\" \"\$R\$l.dd\" 2>/dev/null && mv -f \"\$R\$l.dd\" \"\$R\$l\"")
        line("  done")
        line("  : > \"\$F/chroot/l2s-stamp\"")
        line("fi")
        // libblfastpath only speeds up proot, so a chroot's programs need not load it.
        val preload = File(parsed.root, "etc/ld.so.preload")
        if (preload.isFile) {
            write(File(dir, "ld.so.preload"), preload.readLines().filterNot { it.contains("libblfastpath") }.joinToString("\n", postfix = "\n"))
            line("b \"\$F/chroot/ld.so.preload\" /etc/ld.so.preload")
        }
        line("export DD_CTX=${q(selfContext())} DD_CWD=${q(parsed.cwd)}")
        line("cd /")
        val groups = selfGroups()
        val priv = "--reuid=$uid --regid=$uid " + if (groups.isNotEmpty()) "--groups=$groups" else "--clear-groups"
        // rseq stays off for proot's sake; a real kernel can have it.
        val guest = parsed.guest.joinToString(" ") { q(it.replace("glibc.pthread.rseq=0:", "")) }
        val asApp = "chroot \"\$R\" /usr/bin/setpriv $priv /bin/sh ${q(dom.path)}"
        line("i=0; while [ \$i -lt 5 ]; do")
        line("  $asApp /bin/sh ${q(File(dir, "linkprobe.sh").path)} 2>/dev/null && break")
        line("  /data/adb/ksud sepolicy apply \"\$F/chroot/sepolicy.rule\" >/dev/null 2>&1; sleep 1; i=\$((i+1))")
        line("done; [ \$i -lt 5 ] || echo 'chroot: the sepolicy rules are not active'")
        line("exec chroot \"\$R\" /usr/bin/setpriv $priv /bin/sh ${q(File(dir, "dom.sh").path)} $guest")
        val script = File(dir, "session.sh")
        write(script, sb.toString())
        script.setReadable(true, false)
        active = true
        Log.i(TAG, "chroot session: ${parsed.binds.size} binds, groups=$groups, context=${selfContext()}")
        return listOf(SU, "-c", "sh ${script.path}")
    }

    @Volatile private var inFront: Boolean? = null

    /**
     * A chroot's processes never get Android's foreground and background moves, so they would stay in top-app
     * after the app leaves the screen, competing with whatever the user went on to.
     */
    fun setInFront(context: Context, front: Boolean) {
        if (!active || inFront == front) return
        inFront = front
        val root = LinuxRuntime.rootDir(context).path
        val set = if (front) "top-app" else "background"
        val cpu = if (front) "top-app" else "foreground"
        val script =
            "R=${q(root)}; for p in /proc/[0-9]*; do [ \"\$(readlink \$p/root 2>/dev/null)\" = \"\$R\" ] || continue; n=\${p#/proc/}; " +
                "echo \$n > /dev/cpuset/$set/cgroup.procs 2>/dev/null; echo \$n > /dev/cpuctl/$cpu/cgroup.procs 2>/dev/null; done"
        Thread({
            try {
                val p = ProcessBuilder(SU, "-c", script).redirectErrorStream(true).start()
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "scheduling group: $e")
            }
        }, "chroot-sched").start()
    }

    /** Stops everything still running with the rootfs as its root (TERM, then KILL after about 3 seconds). */
    fun cleanup(context: Context) {
        val root = LinuxRuntime.rootDir(context).path
        val script =
            "R=${q(root)}; " +
                "k() { for p in /proc/[0-9]*; do [ \"\$(readlink \$p/root 2>/dev/null)\" = \"\$R\" ] && echo \${p#/proc/}; done; }; " +
                "L=\$(k); [ -z \"\$L\" ] && exit 0; kill -TERM \$L 2>/dev/null; i=0; " +
                "while [ \$i -lt 15 ] && [ -n \"\$(k)\" ]; do sleep 0.2; i=\$((i+1)); done; " +
                "L=\$(k); [ -n \"\$L\" ] && kill -KILL \$L 2>/dev/null; exit 0"
        try {
            val p = ProcessBuilder(SU, "-c", script).redirectErrorStream(true).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "cleanup: $e")
        }
    }
}
