# Windows setup

Build and run robCryptoData on a fresh Windows machine.

## 1. Install dependencies

```powershell
winget install -e --id Git.Git
winget install -e --id Oracle.JDK.25
winget install -e --id Apache.Maven
```

Open a new PowerShell afterwards so PATH updates take effect.

## 2. Get the source and build

```powershell
git clone https://github.com/dbder/robCryptoData.git
cd robCryptoData
mvn package
```

## 3. Run

```powershell
java -jar target\robCryptoData-1.0-SNAPSHOT.jar
```

Writes `data<yyyy-MM-dd>.csv`, `.ods` and `.xlsx` into the current directory.
To use a custom symbol list, place a file named `symbols` (one symbol per
line, `#` for comments) next to the jar; otherwise the built-in list is used.
