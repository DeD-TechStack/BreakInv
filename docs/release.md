# BreakInv — Release & Installation Guide

## Official Downloads

Download BreakInv from the **GitHub Releases page** of this repository.

Each release ships two installers and their SHA256 checksum files:

| Platform | File |
|----------|------|
| Windows 10 / 11 (x64) | `BreakInv-<version>-windows-x64.msi` |
| Linux — Debian / Ubuntu (amd64) | `BreakInv-<version>-linux-amd64.deb` |

Download only from the official releases page. Do not install files from unofficial sources.

---

## Verifying SHA256 Checksums

Each release includes a `.sha256` file alongside its installer.
Verifying the checksum confirms the file was not corrupted or tampered with in transit.

**Windows (PowerShell):**
```powershell
# 1. Print the hash of the downloaded file
(Get-FileHash "BreakInv-X.Y.Z-windows-x64.msi" -Algorithm SHA256).Hash

# 2. Print the expected hash from the checksum file
Get-Content "BreakInv-X.Y.Z-windows-x64.msi.sha256"

# Both values must match (case-insensitive).
```

**Linux:**
```bash
sha256sum -c BreakInv-X.Y.Z-linux-amd64.deb.sha256
# Expected output: BreakInv-X.Y.Z-linux-amd64.deb: OK
```

---

## Installing on Windows

1. Download `BreakInv-<version>-windows-x64.msi` (and optionally verify the SHA256).
2. Double-click the `.msi` file.
3. If Windows SmartScreen shows a warning — read the section below before clicking anything.
4. Follow the installer wizard (Next → Install → Finish).
5. BreakInv will appear in the **Start Menu** under the **BreakInv** group.
6. A desktop shortcut is also created.

### Uninstalling

Open **Settings → Apps → Installed apps**, search for *BreakInv*, and choose **Uninstall**.
Your data file (`breakinv.db`) is kept in the installation directory; delete it manually if you want a clean removal.

---

## Windows SmartScreen Warning

When you run the installer for the first time, Windows Defender SmartScreen may show:

> **"Windows protected your PC"**
> Windows SmartScreen prevented an unrecognized app from starting.

**Why this happens:**
SmartScreen scores applications based on two factors — (1) how many machines have run that exact file, and (2) whether the file carries a verified Authenticode digital signature. New releases from new publishers always start with zero reputation, regardless of whether the software is safe.

**What to do if you trust the source:**
1. Click **More info** (small link below the warning text).
2. Click **Run anyway**.
3. The installer will proceed normally.

This is safe to do when the file was downloaded from the official GitHub Releases page and its SHA256 checksum matches.

**Note:** The warning frequency decreases naturally as more users install the app and Windows builds up its reputation score. It is not a sign that the software is malicious.

---

## Code Signing Status

BreakInv is **not currently code-signed** with an Authenticode certificate.

This means:
- Windows SmartScreen will warn on first run (see above).
- Some managed enterprise environments may block unsigned MSI installers by policy.
- The app is otherwise fully functional on consumer Windows 10 and 11.

---

## Future Signing Options

Eliminating SmartScreen warnings requires a valid Authenticode code-signing certificate.
Options available to independent developers and small publishers:

| Option | Approx. cost | SmartScreen effect |
|--------|-------------|-------------------|
| OV Authenticode certificate | $200–500 / yr | Removes *unknown publisher* but reputation still builds gradually |
| EV (Extended Validation) certificate | $400–700 / yr | Bypasses SmartScreen immediately upon first install |
| **Azure Trusted Signing** | ~$9.99 / mo | Microsoft-managed; good SmartScreen reputation; easiest modern option |
| Microsoft Store / MSIX | Free to submit | Best SmartScreen outcome; requires Store review and developer account |

> **Azure Trusted Signing** (`trustedsigning.azure.com`) is the recommended path for new
> projects. It integrates with GitHub Actions via the `azure/trusted-signing-action` action
> and costs significantly less than a traditional EV certificate.

---

## Installing on Linux (Debian / Ubuntu)

```bash
# Install
sudo dpkg -i BreakInv-<version>-linux-amd64.deb

# If dpkg reports missing dependencies, run:
sudo apt-get install -f

# Uninstall
sudo dpkg -r breakinv
```

After installation the app appears in your application launcher under the **Finance** category.

---

## Application Data

BreakInv stores all data locally in a single SQLite file:

```
breakinv.db
```

The file is created automatically in the application's working directory on first launch.
No data is sent to external servers. All investment records remain on your machine.

---

## Building from Source

See the project README for instructions on running and packaging from source with Maven.

```bash
mvn clean package          # build fat JAR
mvn javafx:run             # run in development mode
```

To produce a local installer manually (requires JDK 21 with jpackage):
```bash
# Windows — adapt paths and version as needed
jpackage \
  --input target/ \
  --main-jar breakinv-0.5.0.jar \
  --main-class com.daniel.main.App \
  --name BreakInv \
  --app-version 0.5.0 \
  --dest output/ \
  --type msi \
  --icon packaging/windows/BreakInv.ico \
  --vendor "DeD TechStack" \
  --win-menu --win-shortcut --win-menu-group BreakInv \
  --win-upgrade-uuid b72a4f3c-9e1d-4a2b-8c5d-f3e7a1b94d2e
```
