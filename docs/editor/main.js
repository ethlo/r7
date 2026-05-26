import * as monaco from 'monaco-editor';
import { configureMonacoYaml } from 'monaco-yaml';
import { parse } from 'yaml';

import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import YamlWorker from 'monaco-yaml/yaml.worker?worker';

window.MonacoEnvironment = {
    getWorker(moduleId, label) {
        if (label === 'yaml') return new YamlWorker();
        return new EditorWorker();
    }
};

const STORAGE_KEY = 'r7_editor_draft';
const THEME_KEY = 'r7_editor_theme';

async function initializeEditor() {
    const schemaUrl = '/schemas/latest.yaml';
    const modelUri = monaco.Uri.parse('file://root/config.yaml');

    // 1. Fetch and configure Schema
    try {
        const response = await fetch(schemaUrl);
        if (response.ok) {
            const yamlString = await response.text();
            const parsedSchema = parse(yamlString);

            configureMonacoYaml(monaco, {
                enableSchemaRequest: false,
                schemas: [{
                    uri: 'http://internal/r7-schema.json',
                    fileMatch: [modelUri.toString()],
                    schema: parsedSchema
                }]
            });
        }
    } catch (error) {
        console.error('Failed to load schema:', error);
    }

    // 2. Load Draft & Theme from LocalStorage
    const defaultTemplate = [
        'gateway:',
        '  port: 8080',
        '  routes:',
        '    - id: "example-route"',
        '      match:',
        '        - Path:',
        '            path: "/api"',
        '      upstream:',
        '        targets:',
        '          - url: "http://upstream-service:8081"'
    ].join('\n');

    const initialConfig = localStorage.getItem(STORAGE_KEY) || defaultTemplate;
    const savedTheme = localStorage.getItem(THEME_KEY) || 'vs-dark';

    // Sync the UI dropdown with the saved theme
    document.getElementById('theme-selector').value = savedTheme;
    updateToolbarStyling(savedTheme);

    // 3. Create Editor
    const editor = monaco.editor.create(document.getElementById('app'), {
        model: monaco.editor.createModel(initialConfig, 'yaml', modelUri),
        theme: savedTheme,
        automaticLayout: true,
        minimap: { enabled: false },
        fontFamily: "'Consolas', 'Courier New', monospace"
    });

    // 4. Auto-save to LocalStorage
    editor.onDidChangeModelContent(() => {
        localStorage.setItem(STORAGE_KEY, editor.getValue());
    });

    // 5. Wire up the Theme Switcher
    document.getElementById('theme-selector').addEventListener('change', (e) => {
        const newTheme = e.target.value;
        monaco.editor.setTheme(newTheme);
        localStorage.setItem(THEME_KEY, newTheme);
        updateToolbarStyling(newTheme);
    });

    // 6. Wire up the "Save As" Button
    document.getElementById('save-btn').addEventListener('click', () => {
        const content = editor.getValue();
        let filename = prompt("Enter a name for your config file:", "r7-config.yaml");

        if (!filename) return;

        if (!filename.endsWith('.yaml') && !filename.endsWith('.yml')) {
            filename += '.yaml';
        }

        const blob = new Blob([content], { type: 'text/yaml' });
        const url = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();

        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    });
}

// Helper to make the toolbar match the editor's light/dark mode
function updateToolbarStyling(theme) {
    const toolbar = document.getElementById('toolbar');
    const title = document.getElementById('app-title');

    if (theme === 'vs') {
        // Light mode
        document.body.style.backgroundColor = '#fffffe';
        toolbar.style.backgroundColor = '#f3f3f3';
        toolbar.style.borderBottomColor = '#cccccc';
        document.body.style.color = '#333333';
    } else {
        // Dark & High Contrast mode
        document.body.style.backgroundColor = theme === 'hc-black' ? '#000000' : '#1e1e1e';
        toolbar.style.backgroundColor = theme === 'hc-black' ? '#000000' : '#252526';
        toolbar.style.borderBottomColor = theme === 'hc-black' ? '#6fc3df' : '#333333';
        document.body.style.color = '#cccccc';
    }
}

initializeEditor();