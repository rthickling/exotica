# Debugging in Cursor or VS Code

Normal use and all automated tests need only Docker with Compose v2+ and `just` 1.56+. Run `just up` to use the product and `just test` to test it. These commands run the language runtimes inside containers.

The optional **Debug stack** configuration runs Java, Python and Angular on your laptop so the editor can stop at breakpoints. It uses a separate PostgreSQL container on `127.0.0.1:55432` and requires these additional host tools:

* Cursor or VS Code with Java language support, Debugger for Java and Python Debugger extensions.
* Node 24 and npm.
* Python 3.12 with `venv` and pip.
* Chrome for the UI debugger.

`just debug-jdk` installs a native Temurin 21.0.13+9 JDK under the ignored `.tools/jdk-21` directory. Downloading, SHA256 verification and extraction run in the Docker tools container, so this step needs no host Python. The selected archive matches the OS and architecture reported by `just`; a Linux container JDK is never installed as a macOS or Windows runtime. macOS uses the archive's `Contents/Home` directory. The recipe also runs the resulting host `java -version` to verify it.

Native JDK archives are configured for Linux, Intel/Apple Silicon macOS and x64 Windows. The pinned Temurin release has no Windows ARM64 JDK; `debug-jdk` reports unsupported targets explicitly. This limit applies to optional host debugging. The ordinary Docker workflow does not use this JDK.

## First-time setup

From the repository root:

```sh
just debug-jdk
```

Configure the editor's Java runtime and JavaSE-21 project runtime to the **absolute path** of `.tools/jdk-21`. Use **Java: Configure Java Runtime** or your local `.vscode/settings.json`, which is ignored by Git. Reload the editor if Java has already imported the project using another JDK. The committed launch configuration points the application debugger at this local JDK; the language server/import runtime must also be configured in the editor.

Install the host UI packages:

```sh
cd ui
npm ci
cd ..
```

In Windows PowerShell, use `npm.cmd ci` if PowerShell's execution policy blocks the `npm.ps1` wrapper. The editor task uses `npx.cmd` on Windows for the same reason.

Create the pricer virtual environment on Linux/macOS:

```sh
python3.12 -m venv pricer/.venv
pricer/.venv/bin/python -m pip install -r pricer/requirements.txt
```

Or in Windows PowerShell:

```powershell
py -3.12 -m venv pricer/.venv
pricer/.venv/Scripts/python.exe -m pip install -r pricer/requirements.txt
```

The virtual environment is ignored by Git. The debugger selects `bin/python` on Linux/macOS and `Scripts/python.exe` on Windows.

## Start and stop

Select **Debug stack** in the editor's Run and Debug panel and press F5. Its preparation task runs `just debug-deps`, which verifies the JDK, stops this checkout's Docker app/pricer/UI services and creates or restarts the named `exotica-debug-db` container. Existing debug database records are preserved. The compound then starts the pricer, Spring application and browser; the UI task runs `ng serve` on `127.0.0.1:4200` with API requests proxied to the host application.

Use the editor's Stop control to stop the compound. Terminate the **ng serve** task through **Tasks: Terminate Task** when finished; it is a background task and can outlive the browser debugger. Stop any previous debug session/task before starting another. If a port is occupied, stop its owner deliberately.

```sh
just debug-clean
```

This stops only this checkout's Docker app/pricer/UI services and `exotica-debug-db`. It leaves all host processes alone and retains the debug database container and its data. `debug-deps` reuses that container next time. `just down` manages the ordinary Compose stack, whose database is separate from the debug database.

The Angular asset configuration reads `fixtures/market.json` and `fixtures/lifecycle-paths.json` from the repository for both development and production builds. The same `/fixtures/` URLs work under `ng serve` and Nginx without maintaining duplicate fixture files.

## Verification

`just test-debug` runs offline installer tests in Docker, covering platform selection, cached runtime validation, macOS layout, ZIP/tar extraction, checksums and preservation of an existing installation on failure. It is included in `just ci`. Native runtime execution has been checked on Linux; archive/layout tests do not establish native macOS or Windows execution.
