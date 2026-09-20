# Maven availability and recommended setup

Checked September 19, 2026. This is an audit and setup guide; no software was installed and no environment settings were changed.

Maven libraries are installed inside Eclipse and VS Code, but no standalone Windows Maven launcher was found in the searched locations. The project also has no Maven Wrapper. This explains why the IDE can support Maven projects while an ordinary terminal cannot run `mvn`.

## What was found

| Check | Result |
| --- | --- |
| `mvn`, `mvn.cmd`, `mvn.bat`, `mvn.exe` command lookup | Not found in the current shell |
| Process/user/machine `Path` | No Maven entry |
| Eclipse m2e | Embedded Maven 3.9.4 libraries; no standalone `bin/mvn.cmd` |
| VS Code Java extension | Embedded Maven 3.9.16 libraries; no standalone launcher found |
| VS Code Maven extension | A wrapper template targeting Maven 3.6.3, not an installed Maven distribution or this project's wrapper |
| User `.m2` folder | Dependency repository cache; no wrapper distribution cache |
| This repository | `pom.xml` exists; `mvnw`, `mvnw.cmd` and wrapper properties do not |
| Java installation | Both `java.exe` and `javac.exe` verified as 17.0.8.1 |
| Java environment | Workspace VS Code settings configure Java for new integrated terminals; ordinary process/user/machine `JAVA_HOME` was unset |

Locations of embedded libraries, for reference only:

```text
C:\Users\raaid\.p2\pool\plugins\org.eclipse.m2e.maven.runtime_3.9.400.20230826-0755\jars
C:\Users\raaid\AppData\Roaming\Code\User\globalStorage\redhat.java\1.56.0\config_win\org.eclipse.osgi\66\0\.cp\jars
```

These IDE-managed library directories are not the recommended paths to add to `Path`.

The search covered readable parts of the user profile, including Downloads, Desktop, Documents, AppData and hidden tools/cache folders; Program Files, Program Files (x86), ProgramData, Applications and Public; and the visible FPGA/vendor tool roots. Common tools, Scoop and Chocolatey installation locations were checked. Only drive C: was exposed.

Ubuntu-20.04 is registered in WSL, but its virtual disk was not opened or the distribution started. Maven might exist inside that separate Linux environment. Protected directories and other users' data were not exhaustively searched. The finding is therefore "no standalone Windows Maven found in the searched locations," not proof that no copy exists anywhere.

## Recommended setup for this workspace

Use a standalone Maven 3 installation to get the existing README commands working, then add a pinned project wrapper during the implementation setup milestone. The current Apache download page lists **Maven 3.9.16** as the stable release; it supports the project's Java 17 installation. Choose the binary ZIP from the [official Maven download page](https://maven.apache.org/download.cgi).

1. Download `apache-maven-3.9.16-bin.zip` and extract it into a user-owned tools directory. The proposed location is `C:\Users\raaid\tools\apache-maven-3.9.16`. Confirm that `bin\mvn.cmd` exists inside it; avoid an unintended extra nested directory.
2. Open Windows **Edit environment variables for your account**. Under user variables, set `JAVA_HOME` to the verified Java directory below. It must point to the directory containing `bin`, not to `bin` itself.
3. Edit the user `Path`, retaining its existing entries, and add `%JAVA_HOME%\bin` and `C:\Users\raaid\tools\apache-maven-3.9.16\bin` as separate entries. A separate `MAVEN_HOME` variable is not necessary for this setup.
4. Close and reopen VS Code and PowerShell so they inherit the new environment. Open a fresh terminal in the repository and run the verification commands below.

This follows Apache's [installation instructions](https://maven.apache.org/install.html): use a JDK, extract the binary distribution, add its `bin` directory to `Path`, and verify in a new shell.

The existing verified Java directory is:

```text
C:\Users\raaid\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.8.v20230831-1047\jre
```

Despite the directory name `jre`, this installation contains a working `javac.exe`. It is sufficient for the immediate Java 17 setup. Since Eclipse manages this location, recheck `JAVA_HOME` if an IDE update later removes it; each teammate should use their own JDK path.

Verification commands, after installing and reopening the terminal:

```powershell
java -version
javac -version
mvn -version
```

Expected: working Java/Javac 17 and a Maven report showing the intended Maven installation and Java 17. Then run the project's baseline checks:

```powershell
mvn test
mvn package
```

Initial dependency/plugin downloads require internet access. These build/test commands have not been run as part of this audit; any failure should be diagnosed before declaring setup complete.

If command lookup still fails after extraction, this direct version check separates Maven installation problems from `Path` problems:

```powershell
& 'C:\Users\raaid\tools\apache-maven-3.9.16\bin\mvn.cmd' -version
```

## Recommended team build command

During implementation setup, generate the wrapper from a working Maven installation:

```powershell
mvn wrapper:wrapper '-Dmaven=3.9.16' '-Dtype=only-script'
```

Review and commit the generated `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties`. All teammates then use the pinned wrapper, with their own JDK configured:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd test
.\mvnw.cmd package
```

The wrapper downloads its pinned Maven distribution when needed; it does not install a JDK. Once generated and committed, another teammate does not need a separate global Maven installation. This setup step has not been performed yet. See [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/).

Return to the [Person 3 task checklist](person-3-task-checklist.md) after the environment milestone.
