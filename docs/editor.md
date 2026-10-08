---
hide:
  - toc
---

# r7 Gateway Configuration Editor

Write your `routes.yaml` here and the editor checks it against r7's config schema as you type. Misspell a filter name, such as `AddResponseHeadr`, and it is flagged on the spot, the way the gateway would refuse it at startup.

<iframe
src="/editor-app/index.html"
style="width: 100%; height: 80vh; min-height: 600px; border: 1px solid #303030; border-radius: 8px; margin-top: 1rem; background: #1e1e1e;"
title="r7 Config Editor"
allowfullscreen>
</iframe>

## How to use this editor
* **Autocomplete:** Press `Ctrl + Space` to view available properties.
* **Validation:** The editor will flag syntax errors and schema violations in real-time.
* **Persistence:** Your work is automatically saved to your local browser storage.