'use strict';
const $ = id => document.getElementById(id);
let csrf = '';
let controller = null;
let operation = 0;
let previousQuestions = [];

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function appendMessage(role, text, error = false) {
  $('welcome').hidden = true;
  const article = element('article', `message ${role}${error ? ' error' : ''}`);
  article.append(element('div', 'message-label', role === 'user' ? 'YOU' : 'AWS SUPPORT · LOCAL'));
  article.append(element('div', 'message-body', text));
  $('messages').append(article);
  scrollChat();
  return article;
}
function scrollChat() { const area = document.querySelector('.chat-area'); area.scrollTop = area.scrollHeight; }
function busy(value) {
  $('send').disabled = value; $('waiting').hidden = !value; $('stop').hidden = !value;
  $('service').disabled = value; $('question').setAttribute('aria-busy', String(value));
}
async function session() {
  const response = await fetch('/api/v1/session');
  if (!response.ok) throw new Error('Cannot establish a local session. Reload the page.');
  csrf = (await response.json()).csrfToken;
}
async function corpus() {
  try {
    const response = await fetch('/api/v1/corpus');
    if (!response.ok) throw new Error('Local database unavailable');
    const data = await response.json();
    $('doc-count').textContent = data.documents.toLocaleString();
    $('chunk-count').textContent = data.chunks.toLocaleString();
    $('corpus-state').textContent = data.state === 'READY' ? Object.keys(data.services).join(' · ') : 'No corpus yet. Run the ingestion command to add documentation.';
    $('snapshot').textContent = data.publishedAt ? `Published ${new Date(data.publishedAt).toLocaleDateString()}. ${data.stale ? 'Source checks are overdue.' : 'Source checks are recent.'}` : 'Waiting for the first documentation snapshot.';
    if (data.lastRefreshError) $('snapshot').textContent += ' Last refresh failed; the previous snapshot is retained.';
    $('mobile-snapshot').textContent = `${data.documents} documents · ${data.stale ? 'Source checks overdue' : 'Local snapshot'}${data.lastRefreshError ? ' · Refresh failed' : ''}`;
  } catch (error) { $('corpus-state').textContent = error.message; $('snapshot').textContent = 'Check the local database and try again.'; }
}
function renderAnswer(data) {
  const article = appendMessage('assistant', '');
  const body = article.querySelector('.message-body');
  body.append(element('h2', 'answer-title', data.message));
  if (data.status === 'ANSWERED') {
    for (const claim of data.claims) {
      body.append(element('blockquote', 'excerpt', claim.text));
      for (const id of claim.citationIds) {
        const citation = data.citations.find(c => c.id === id);
        if (!citation) continue;
        const url = new URL(citation.sourceUrl);
        if (url.protocol !== 'https:' || url.hostname !== 'docs.aws.amazon.com') continue;
        const link = element('a', 'citation-title', `[${id}] ${citation.title} ↗`);
        link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer'; body.append(link);
      }
    }
    const details = element('details'); details.append(element('summary', '', 'Inspect local evidence & provenance'));
    for (const citation of data.citations) {
      details.append(element('p', '', `${citation.id} · ${citation.heading}\nFetched ${new Date(citation.fetchedAt).toLocaleString()}\nSpan ${citation.spanId}`));
    }
    body.append(details);
  } else {
    body.append(element('p', 'answer-detail', data.status === 'CLARIFICATION_REQUIRED' ? 'Include the service, the error message, and the behavior you want explained. Do not include credentials.' : 'Try a narrower question or add relevant documents to the local corpus. No answer was inferred from outside knowledge.'));
  }
  body.append(element('div', 'answer-meta', `${data.answerMode} · ${data.cacheDisposition === 'EXACT' ? 'Validated cache hit' : data.cacheDisposition === 'COALESCED' ? 'Shared identical request' : 'Evidence checked'} · ${(data.durationMs / 1000).toFixed(1)}s · Snapshot ${data.corpusGeneration.slice(0, 8)}`));
  scrollChat();
}
$('chat-form').addEventListener('submit', async event => {
  event.preventDefault();
  const question = $('question').value.trim();
  if (!question || controller) return;
  const current = ++operation;
  controller = new AbortController(); busy(true); appendMessage('user', question);
  $('question').value = ''; $('char-count').textContent = '0';
  try {
    if (!csrf) await session();
    const response = await fetch('/api/v1/chat', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, signal: controller.signal,
      body: JSON.stringify({ question, previousQuestions: previousQuestions.slice(-3), filters: { service: $('service').value } }) });
    const data = await response.json();
    if (current !== operation) return;
    if (!response.ok) { if (response.status === 403) csrf = ''; throw new Error(data.message || 'The local service could not complete the request.'); }
    renderAnswer(data); previousQuestions.push(question);
  } catch (error) {
    if (current === operation && error.name !== 'AbortError') appendMessage('assistant', error.message, true);
  } finally { if (current === operation) { controller = null; busy(false); $('question').focus(); } }
});
$('stop').addEventListener('click', () => {
  if (controller) controller.abort();
  operation++; controller = null; busy(false);
  appendMessage('assistant', 'Stopped waiting. The local model may still finish its current operation before accepting more work.');
});
$('clear').addEventListener('click', () => {
  if (controller) controller.abort();
  operation++; controller = null; busy(false); previousQuestions = [];
  $('messages').replaceChildren(); $('welcome').hidden = false; $('question').focus();
});
$('question').addEventListener('input', () => { $('char-count').textContent = String($('question').value.length); });
$('question').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chat-form').requestSubmit(); } });
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => { $('question').value = button.dataset.question; $('question').dispatchEvent(new Event('input')); $('question').focus(); }));
$('refresh-status').addEventListener('click', corpus);
session().catch(() => {}); corpus();
