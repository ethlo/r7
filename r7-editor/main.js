import * as monaco from 'monaco-editor';
import {configureMonacoYaml} from 'monaco-yaml';
import {parse} from 'yaml';

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

// --- CUSTOM MODAL SYSTEM ---
function showModal({title, message, type = 'confirm', inputValue = '', confirmText = 'OK', danger = false}) {
    return new Promise((resolve) => {
        const overlay = document.getElementById('modal-overlay');
        const titleEl = document.getElementById('modal-title');
        const msgEl = document.getElementById('modal-message');
        const inputEl = document.getElementById('modal-input');
        const btnCancel = document.getElementById('modal-cancel');
        const btnConfirm = document.getElementById('modal-confirm');

        titleEl.textContent = title;
        msgEl.textContent = message;

        btnConfirm.textContent = confirmText;
        btnConfirm.className = danger ? 'danger' : 'primary';

        if (type === 'prompt') {
            inputEl.classList.remove('hidden');
            inputEl.value = inputValue;
        } else {
            inputEl.classList.add('hidden');
        }

        overlay.classList.add('active');
        if (type === 'prompt') inputEl.focus();
        else btnConfirm.focus();

        const cleanup = () => {
            overlay.classList.remove('active');
            btnCancel.removeEventListener('click', onCancel);
            btnConfirm.removeEventListener('click', onConfirm);
            inputEl.removeEventListener('keydown', onEnter);
        };

        const onCancel = () => {
            cleanup();
            resolve(null);
        };
        const onConfirm = () => {
            cleanup();
            resolve(type === 'prompt' ? inputEl.value : true);
        };
        const onEnter = (e) => {
            if (e.key === 'Enter') onConfirm();
        };

        btnCancel.addEventListener('click', onCancel);
        btnConfirm.addEventListener('click', onConfirm);
        inputEl.addEventListener('keydown', onEnter);
    });
}

const hoverMetadata = {};
let isHoverEnabled = true;

// Holds static descriptions for the root configuration keys
const rootPropertyDocs = {
    "global_filters": "A list of filters applied globally to every request passing through the gateway.",
    "routes": "The routing table defining how incoming traffic is matched and forwarded.",
    "id": "A unique identifier for this route. Used for logging, metrics, and fallback references.",
    "match": "Conditions that must be met for this route to handle a request. If omitted, all requests match.",
    "filters": "A list of processing steps applied to the request before forwarding, and to the response before returning.",
    "upstream": "Configuration for routing traffic to backend services.",
    "health_check": "Active health checking configuration for the backend targets.",
    "fallback": "Fallback routing behavior triggered when all upstream targets fail.",
    "journal": "Observability and logging configuration for this specific route."
};

async function initializeEditor() {
    const schemaUrl = '/schemas/latest.yaml';
    const modelUri = monaco.Uri.parse('file:///config.yaml'); // Use 3 slashes

    try {
        const cacheBuster = Date.now();
        const response = await fetch(`${schemaUrl}?v=${cacheBuster}`);
        if (response.ok) {
            const yamlString = await response.text();
            const parsedSchema = parse(yamlString);

            if (parsedSchema.$defs) {
                ['filter', 'predicate'].forEach(category => {
                    const def = parsedSchema.$defs[category];

                    if (def && def.anyOf) {
                        const objectDefinition = def.anyOf.find(node => node.properties);

                        if (objectDefinition && objectDefinition.properties) {
                            for (const [componentName, schemaNode] of Object.entries(objectDefinition.properties)) {
                                if (schemaNode.description) {

                                    // 1. Grab the component metadata
                                    const meta = {
                                        description: schemaNode.description,
                                        required: schemaNode.required ? schemaNode.required.map(r => `\`${r}\``).join(', ') : 'None',
                                        parameters: {} // New!
                                    };

                                    // 2. Loop through its properties and grab those descriptions too
                                    if (schemaNode.properties) {
                                        for (const [paramName, paramNode] of Object.entries(schemaNode.properties)) {
                                            if (paramNode.description) {
                                                meta.parameters[paramName] = paramNode.description;
                                            }
                                        }
                                    }

                                    hoverMetadata[componentName] = meta;
                                }
                            }
                        }
                    }
                });
            }

            const internalSchemaUri = `http://internal/r7-schema-${cacheBuster}.json`;

            configureMonacoYaml(monaco, {
                enableSchemaRequest: false,
                validate: true,
                // CRITICAL: Disable native hover so it doesn't block our custom provider
                hover: false,
                completion: true,
                schemas: [{uri: internalSchemaUri, fileMatch: ['*'], schema: parsedSchema}]
            });
        }
    } catch (error) {
        console.error('Failed to load schema:', error);
    }

    const initialConfig = localStorage.getItem(STORAGE_KEY) || defaultTemplate;
    const savedTheme = localStorage.getItem(THEME_KEY) || 'vs-dark';

    document.getElementById('theme-selector').value = savedTheme;
    updateThemeVariables(savedTheme);

    const editor = monaco.editor.create(document.getElementById('app'), {
        model: monaco.editor.createModel(initialConfig, 'yaml', modelUri),
        theme: savedTheme, automaticLayout: true, minimap: {enabled: false},
        fontFamily: "'Consolas', 'Courier New', monospace", wordBasedSuggestions: 'off',
        suggest: {showSnippets: true, showInlineDetails: true}
    });

    editor.onDidChangeModelContent(() => localStorage.setItem(STORAGE_KEY, editor.getValue()));

    // --- THE CUSTOM HOVER PROVIDER ---
    monaco.languages.registerHoverProvider('yaml', {
        provideHover: function (model, position) {

            // THE KILL SWITCH
            if (!isHoverEnabled) return null;
            
            const word = model.getWordAtPosition(position);
            if (!word) return null;

            // Scenario 1: Is it a static root property? (e.g. upstream, journal)
            if (rootPropertyDocs[word.word]) {
                return {
                    range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
                    contents: [
                        { value: `**${word.word}**` },
                        { value: rootPropertyDocs[word.word] }
                    ]
                };
            }

            // Scenario 2: Is it a root component? (e.g. AddQueryParameter)
            const componentMeta = hoverMetadata[word.word];
            if (componentMeta) {
                // Dynamically build the lowercase anchor link
                const docLink = `https://r7.ethlo.com/config/#${word.word.toLowerCase()}`;

                return {
                    range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
                    contents: [
                        { value: `**${word.word}**` },
                        { value: componentMeta.description },
                        { value: `**Required:** ${componentMeta.required}` },
                        { value: `[Read the documentation \u2197](${docLink})` } // Markdown link!
                    ]
                };
            }

            // Scenario 3: Is it a nested parameter?
            const currentLine = model.getLineContent(position.lineNumber);
            if (!currentLine.includes(':') || currentLine.indexOf(':') < word.startColumn) {
                return null;
            }

            const currentIndent = currentLine.search(/\S|$/);
            let parentKey = null;

            for (let i = position.lineNumber - 1; i >= 1; i--) {
                const scanLine = model.getLineContent(i);
                if (scanLine.trim() === '') continue;

                const scanIndent = scanLine.search(/\S|$/);
                if (scanIndent < currentIndent) {
                    const match = scanLine.match(/(?:-\s*)?([a-zA-Z0-9_]+)\s*:/);
                    if (match) {
                        parentKey = match[1];
                    }
                    break;
                }
            }

            if (parentKey && hoverMetadata[parentKey] && hoverMetadata[parentKey].parameters[word.word]) {
                const paramDescription = hoverMetadata[parentKey].parameters[word.word];

                // Build the link for the parent component here too!
                const docLink = `https://r7.ethlo.com/config/#${parentKey.toLowerCase()}`;

                return {
                    range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
                    contents: [
                        { value: `**${parentKey}** > \`${word.word}\`` },
                        { value: paramDescription },
                        { value: `[View ${parentKey} documentation \u2197](${docLink})` }
                    ]
                };
            }

            return null;
        }
    });
    // ---------------------------------

    document.getElementById('save-btn').addEventListener('click', async () => {
        let filename = await showModal({
            type: 'prompt',
            title: 'Save Configuration',
            message: 'Enter a name for your config file:',
            inputValue: 'r7-config.yaml',
            confirmText: 'Save'
        });

        if (!filename) return;
        if (!filename.endsWith('.yaml') && !filename.endsWith('.yml')) filename += '.yaml';

        const blob = new Blob([editor.getValue()], {type: 'text/yaml'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    });

    document.getElementById('clear-btn').addEventListener('click', async () => {
        const confirmed = await showModal({
            title: 'Clear Editor',
            message: 'Are you sure you want to permanently erase the current configuration?',
            confirmText: 'Clear',
            danger: true
        });
        if (confirmed) editor.setValue('');
    });

    // Toggle hover state
    document.getElementById('hover-toggle').addEventListener('change', (e) => {
        isHoverEnabled = e.target.checked;
    });

    document.getElementById('reset-btn').addEventListener('click', async () => {
        const confirmed = await showModal({
            title: 'Load Example',
            message: 'This will overwrite your current draft with the default example. Continue?',
            confirmText: 'Overwrite',
            danger: true
        });
        if (confirmed) editor.setValue(defaultTemplate);
    });

    document.getElementById('fullscreen-btn').addEventListener('click', () => {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(err => console.error(err));
        } else {
            document.exitFullscreen();
        }
    });

    document.addEventListener('fullscreenchange', () => {
        const btn = document.getElementById('fullscreen-btn');
        if (document.fullscreenElement) {
            btn.innerHTML = `<svg viewBox="0 0 24 24"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg> Exit Fullscreen`;
        } else {
            btn.innerHTML = `<svg viewBox="0 0 24 24"><path d="M5 5h5v2H7v3H5V5zm9 0h5v5h-2V7h-3V5zm5 14h-5v-2h3v-3h2v5zm-14 0v-5h2v3h3v2H5z"/></svg> Fullscreen`;
        }
    });
}

function updateThemeVariables(theme) {
    const root = document.documentElement;
    if (theme === 'vs') {
        root.style.setProperty('--bg-main', '#fffffe');
        root.style.setProperty('--bg-toolbar', '#f3f3f3');
        root.style.setProperty('--border-color', '#cccccc');
        root.style.setProperty('--text-main', '#333333');
        root.style.setProperty('--btn-hover', 'rgba(0, 0, 0, 0.05)');
    } else if (theme === 'hc-black') {
        root.style.setProperty('--bg-main', '#000000');
        root.style.setProperty('--bg-toolbar', '#000000');
        root.style.setProperty('--border-color', '#6fc3df');
        root.style.setProperty('--text-main', '#ffffff');
        root.style.setProperty('--btn-hover', 'rgba(111, 195, 223, 0.2)');
    } else { // vs-dark
        root.style.setProperty('--bg-main', '#1e1e1e');
        root.style.setProperty('--bg-toolbar', '#252526');
        root.style.setProperty('--border-color', '#333333');
        root.style.setProperty('--text-main', '#cccccc');
        root.style.setProperty('--btn-hover', 'rgba(255, 255, 255, 0.1)');
    }
}

initializeEditor();