# XMU Rollcall Dashboard Package

This directory contains the Python package used by the desktop dashboard,
including rollcall monitoring, course rollcall summaries, and authorized LNT
courseware downloads. The desktop UI is implemented with PySide6.
Install runtime dependencies from the repository root with:

```powershell
conda run -n xmu-rollcall-dashboard python -m pip install -r .\xmu-rollcall-cli\requirements.txt pyinstaller
```

Run the dashboard during development:

```powershell
$env:PYTHONPATH=(Resolve-Path .\xmu-rollcall-cli).Path
conda run -n xmu-rollcall-dashboard python -m xmu_rollcall.desktop
```
