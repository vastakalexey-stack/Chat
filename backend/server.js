import 'dotenv/config';
import { randomUUID } from 'node:crypto';
import express from 'express';
import cors from 'cors';
import rateLimit from 'express-rate-limit';

const app = express();
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '32kb' }));
app.use('/api/', rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: Number(process.env.RATE_LIMIT_MAX || 30),
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Слишком много запросов. Попробуйте позже.' }
}));

const {
  YANDEX_API_KEY,
  YANDEX_FOLDER_ID,
  GIGACHAT_AUTH_KEY,
  GIGACHAT_SCOPE = 'GIGACHAT_API_PERS'
} = process.env;
const YANDEX_MODEL = process.env.YANDEX_MODEL || 'yandexgpt-5.1';
const GIGACHAT_MODEL = process.env.GIGACHAT_MODEL || 'GigaChat-2-Pro';
const YANDEX_URL = 'https://ai.api.cloud.yandex.net/v1/responses';
const GIGACHAT_TOKEN_URL = process.env.GIGACHAT_TOKEN_URL || 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth';
const GIGACHAT_API_URL = process.env.GIGACHAT_API_URL || 'https://api.giga.chat/v1/chat/completions';
const YANDEX_WEB_SEARCH_ENABLED = process.env.YANDEX_WEB_SEARCH_ENABLED !== 'false';

let gigaToken = null;
let gigaTokenExpiresAt = 0;

app.get('/health', (_req, res) => res.json({
  ok: true,
  app: 'AI Duo',
  providers: ['YandexGPT', 'GigaChat'],
  yandexFeatures: ['long-context-chat', 'web-search']
}));

function yandexModelUri() {
  if (!YANDEX_FOLDER_ID) throw new Error('На сервере не указан YANDEX_FOLDER_ID.');
  return `gpt://${YANDEX_FOLDER_ID}/${YANDEX_MODEL}`;
}

// Yandex Responses API supports the native Web Search tool and returns source annotations.
async function askYandex(input, instructions = 'Ты — YandexGPT, помощник AI Duo. Отвечай по-русски, ясно и полезно. Проверяй актуальные сведения через поиск в интернете, когда вопрос зависит от свежих данных. Не выдумывай факты и указывай, если данных недостаточно.') {
  if (!YANDEX_API_KEY || !YANDEX_FOLDER_ID) {
    throw new Error('На сервере не настроен доступ к YandexGPT (YANDEX_API_KEY и YANDEX_FOLDER_ID).');
  }
  const body = {
    model: yandexModelUri(),
    instructions,
    input,
    temperature: 0.4,
    max_output_tokens: 3000
  };
  if (YANDEX_WEB_SEARCH_ENABLED) {
    body.tools = [{ type: 'web_search', search_context_size: 'medium' }];
  }
  const response = await fetch(YANDEX_URL, {
    method: 'POST',
    headers: {
      Authorization: `Api-Key ${YANDEX_API_KEY}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('Yandex AI Studio Responses API error:', response.status, data);
    throw new Error(`YandexGPT вернул ошибку (${response.status}). Проверьте ключ, каталог и доступ к Responses API.`);
  }
  const answer = data.output_text?.trim()
    || (data.output || [])
      .filter(item => item.type === 'message')
      .flatMap(item => item.content || [])
      .filter(item => item.type === 'output_text')
      .map(item => item.text || '')
      .join('\n')
      .trim();
  if (!answer) throw new Error('YandexGPT вернул пустой ответ.');
  const sources = (data.output || [])
    .flatMap(item => item.content || [])
    .flatMap(item => item.annotations || [])
    .filter(item => item.type === 'url_citation' && item.url)
    .map(item => ({ title: item.title || item.url, url: item.url }))
    .filter((item, index, all) => all.findIndex(x => x.url === item.url) === index);
  return { text: answer, sources };
}

async function getGigaToken() {
  if (gigaToken && Date.now() < gigaTokenExpiresAt - 60000) return gigaToken;
  if (!GIGACHAT_AUTH_KEY) {
    throw new Error('На сервере не указан GIGACHAT_AUTH_KEY.');
  }
  const response = await fetch(GIGACHAT_TOKEN_URL, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${GIGACHAT_AUTH_KEY}`,
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
      RqUID: randomUUID()
    },
    body: new URLSearchParams({ scope: GIGACHAT_SCOPE }),
    signal: AbortSignal.timeout(30000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data.access_token) {
    console.error('GigaChat OAuth error:', response.status, data);
    throw new Error(`Не удалось авторизоваться в GigaChat (${response.status}). Проверьте ключ и сертификаты Минцифры.`);
  }
  gigaToken = data.access_token;
  gigaTokenExpiresAt = Number(data.expires_at || 0) * 1000 || Date.now() + 25 * 60 * 1000;
  return gigaToken;
}

async function askGiga(messages) {
  const token = await getGigaToken();
  const response = await fetch(GIGACHAT_API_URL, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
    body: JSON.stringify({
      model: GIGACHAT_MODEL,
      messages,
      temperature: 0.4,
      max_tokens: 3000
    }),
    signal: AbortSignal.timeout(90000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('GigaChat API error:', response.status, data);
    throw new Error(`GigaChat вернул ошибку (${response.status}). Проверьте модель, тариф и доступ к API.`);
  }
  const answer = data.choices?.[0]?.message?.content?.trim();
  if (!answer) throw new Error('GigaChat вернул пустой ответ.');
  return answer;
}

app.post('/api/team-chat', async (req, res) => {
  const message = String(req.body?.message || '').trim();
  const history = Array.isArray(req.body?.history) ? req.body.history.slice(-12) : [];
  if (!message || message.length > 8000) {
    return res.status(400).json({ error: 'Сообщение должно содержать от 1 до 8000 символов.' });
  }
  const transcript = history
    .filter(item => item && ['user', 'assistant'].includes(item.role) && typeof item.content === 'string')
    .map(item => `${item.role === 'user' ? 'Пользователь' : 'Ассистент'}: ${item.content.slice(0, 4000)}`)
    .join('\n\n');
  const prompt = `${transcript ? `Предыдущая беседа:\n${transcript}\n\n` : ''}Новый вопрос пользователя:\n${message}`;

  try {
    const firstResult = await askYandex(
      prompt,
      'Ты — YandexGPT, главный помощник команды AI Duo. Поддерживай контекст беседы. Если вопрос требует актуальных сведений, используй встроенный веб-поиск Яндекса. Для найденных фактов опирайся на источники и не выдумывай ссылки. Отвечай по-русски.'
    );
    const first = firstResult.text;

    const second = await askGiga([
      { role: 'system', content: 'Ты — GigaChat, независимый эксперт команды AI Duo. Проверь ответ YandexGPT: найди ошибки, пропуски, спорные утверждения и полезные дополнения. Не соглашайся автоматически. Отвечай по-русски.' },
      { role: 'user', content: `Контекст беседы и вопрос пользователя:\n${prompt}\n\nЧерновик YandexGPT:\n${first}\n\nДай критическую оценку и дополнения.` }
    ]);

    const jointResult = await askYandex(
      `Вопрос и контекст:\n${prompt}\n\nЧерновик YandexGPT:\n${first}\n\nПроверка GigaChat:\n${second}\n\nСформируй точный и понятный общий итог. Сохрани полезные ссылки на источники, если они есть в черновике.`,
      'Ты — YandexGPT, ведущий совместного ответа AI Duo. Учти независимую проверку GigaChat. Не скрывай обоснованные разногласия, не выдумывай согласие и укажи оставшуюся неопределённость. Отвечай по-русски.'
    );

    res.json({ yandexgpt: first, gigachat: second, joint: jointResult.text, sources: firstResult.sources });
  } catch (error) {
    console.error('AI Duo request failed:', error);
    res.status(502).json({ error: error.message || 'Не удалось получить ответы от ИИ.' });
  }
});

const port = Number(process.env.PORT || 8787);
app.listen(port, () => console.log(`AI Duo Russia backend listening on port ${port}`));
