# Windows setup

Build and run robCryptoData on a fresh Windows machine. Only dependency: JDK 25.
Maven is bundled via the Maven wrapper (`mvnw.cmd`), no Git needed.

## 1. Install JDK 25

Download and run the installer (accepts all defaults, adds `java` to PATH):

<https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.msi>

Or from PowerShell:

```powershell
Invoke-WebRequest https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.msi -OutFile jdk25.msi
Start-Process msiexec -ArgumentList '/i jdk25.msi /qn' -Wait -Verb RunAs
```

Open a new PowerShell afterwards and check with `java -version`.

## 2. Get the source and build

Download <https://github.com/dbder/robCryptoData/archive/refs/heads/main.zip>,
extract it, then in PowerShell:

```powershell
cd robCryptoData-main
.\mvnw.cmd package
```

(Or `git clone https://github.com/dbder/robCryptoData.git` if Git is available.)

## 3. Run

```powershell
java -jar target\robCryptoData-1.0-SNAPSHOT.jar
```

Writes `data<yyyy-MM-dd>.csv`, `.ods` and `.xlsx` into the current directory.
To use a custom symbol list, place a file named `symbols` (one symbol per
line, `#` for comments) next to the jar; otherwise the built-in list is used.
