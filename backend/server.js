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
  standardHeaders: 'draft-7',
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
const YANDEX_URL = 'https://ai.api.cloud.yandex.net/v1/chat/completions';
const GIGACHAT_TOKEN_URL = process.env.GIGACHAT_TOKEN_URL || 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth';
const GIGACHAT_API_URL = process.env.GIGACHAT_API_URL || 'https://api.giga.chat/v1/chat/completions';

let gigaToken = null;
let gigaTokenExpiresAt = 0;

app.get('/health', (_req, res) => res.json({
  ok: true,
  app: 'AI Duo',
  providers: ['YandexGPT', 'GigaChat']
}));

function yandexModelUri() {
  if (!YANDEX_FOLDER_ID) throw new Error('На сервере не указан YANDEX_FOLDER_ID.');
  return `gpt://${YANDEX_FOLDER_ID}/${YANDEX_MODEL}`;
}

async function askYandex(messages) {
  if (!YANDEX_API_KEY || !YANDEX_FOLDER_ID) {
    throw new Error('На сервере не настроен доступ к YandexGPT (YANDEX_API_KEY и YANDEX_FOLDER_ID).');
  }
  const response = await fetch(YANDEX_URL, {
    method: 'POST',
    headers: {
      Authorization: `Api-Key ${YANDEX_API_KEY}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: yandexModelUri(),
      messages,
      temperature: 0.4,
      max_tokens: 3000
    }),
    signal: AbortSignal.timeout(90000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('YandexGPT API error:', response.status, data);
    throw new Error(`YandexGPT вернул ошибку (${response.status}). Проверьте ключ, каталог и доступ к модели.`);
  }
  const answer = data.choices?.[0]?.message?.content?.trim();
  if (!answer) throw new Error('YandexGPT вернул пустой ответ.');
  return answer;
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
  if (!message || message.length > 8000) {
    return res.status(400).json({ error: 'Сообщение должно содержать от 1 до 8000 символов.' });
  }

  try {
    const first = await askYandex([
      { role: 'system', content: 'Ты — YandexGPT, первый участник команды AI Duo. Отвечай по-русски, ясно и полезно. Отмечай неопределённость и не выдумывай факты.' },
      { role: 'user', content: message }
    ]);

    const second = await askGiga([
      { role: 'system', content: 'Ты — GigaChat, независимый эксперт команды AI Duo. Проверь ответ YandexGPT: найди ошибки, пропуски, спорные утверждения и полезные дополнения. Не соглашайся автоматически. Отвечай по-русски.' },
      { role: 'user', content: `Вопрос пользователя:\n${message}\n\nЧерновик YandexGPT:\n${first}\n\nДай критическую оценку и дополнения.` }
    ]);

    const joint = await askYandex([
      { role: 'system', content: 'Ты — YandexGPT, ведущий совместного ответа AI Duo. Учти независимую проверку GigaChat. Не скрывай обоснованные разногласия, не выдумывай согласие и укажи оставшуюся неопределённость.' },
      { role: 'user', content: `Вопрос: ${message}\n\nЧерновик YandexGPT:\n${first}\n\nПроверка GigaChat:\n${second}\n\nСформируй точный и понятный общий итог по-русски.` }
    ]);

    res.json({ yandexgpt: first, gigachat: second, joint });
  } catch (error) {
    console.error('AI Duo request failed:', error);
    res.status(502).json({ error: error.message || 'Не удалось получить ответы от ИИ.' });
  }
});

const port = Number(process.env.PORT || 8787);
app.listen(port, () => console.log(`AI Duo Russia backend listening on port ${port}`));
