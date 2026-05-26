import * as monaco from 'monaco-editor';
import { configureMonacoYaml } from 'monaco-yaml';
import { parse } from 'yaml';

import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import YamlWorker from 'monaco-yaml/yaml.worker?worker';

import defaultTemplate from './default-config.yaml?raw';

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

    const initialConfig = localStorage.getItem(STORAGE_KEY) || defaultTemplate;
    const savedTheme = localStorage.getItem(THEME_KEY) || 'vs-dark';

    document.getElementById('theme-selector').value = savedTheme;
    updateToolbarStyling(savedTheme);

    // 3. Create Editor
    const editor = monaco.editor.create(document.getElementById('app'), {
        model: monaco.editor.createModel(initialConfig, 'yaml', modelUri),
        theme: savedTheme,
        automaticLayout: true,
        minimap: { enabled: false },
        fontFamily: "'Consolas', 'Courier New', monospace",

        // --- THE AUTOCOMPLETE FIX ---

        // 1. Disable text-based suggestions to prevent collision with schema offsets
        wordBasedSuggestions: 'off',

        // 2. Ensure Monaco processes the schema's $1 placeholders as actual cursor tab-stops
        suggest: {
            showSnippets: true,
        }
    });

    editor.onDidChangeModelContent(() => {
        localStorage.setItem(STORAGE_KEY, editor.getValue());
    });

    document.getElementById('theme-selector').addEventListener('change', (e) => {
        const newTheme = e.target.value;
        monaco.editor.setTheme(newTheme);
        localStorage.setItem(THEME_KEY, newTheme);
        updateToolbarStyling(newTheme);
    });

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

    // Clear the editor completely
    document.getElementById('clear-btn').addEventListener('click', () => {
        if (confirm("Are you sure you want to clear the editor?")) {
            editor.setValue('');
        }
    });

    // Reload the default template
    document.getElementById('reset-btn').addEventListener('click', () => {
        if (confirm("This will overwrite your current draft. Continue?")) {
            editor.setValue(defaultTemplate);
        }
    });
}

function updateToolbarStyling(theme) {
    const toolbar = document.getElementById('toolbar');
    const title = document.getElementById('app-title');

    if (theme === 'vs') {
        document.body.style.backgroundColor = '#fffffe';
        toolbar.style.backgroundColor = '#f3f3f3';
        toolbar.style.borderBottomColor = '#cccccc';
        document.body.style.color = '#333333';
    } else {
        document.body.style.backgroundColor = theme === 'hc-black' ? '#000000' : '#1e1e1e';
        toolbar.style.backgroundColor = theme === 'hc-black' ? '#000000' : '#252526';
        toolbar.style.borderBottomColor = theme === 'hc-black' ? '#6fc3df' : '#333333';
        document.body.style.color = '#cccccc';
    }
}

initializeEditor();