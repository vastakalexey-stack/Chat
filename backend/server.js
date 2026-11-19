import 'dotenv/config';
import express from 'express';
import cors from 'cors';

const app = express();
app.use(cors());
app.use(express.json({ limit: '64kb' }));

const { OPENAI_API_KEY, GEMINI_API_KEY } = process.env;
const OPENAI_MODEL = process.env.OPENAI_MODEL || 'gpt-4.1-mini';
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.5-flash';

app.get('/health', (_req, res) => res.json({ ok: true, app: 'AI Duo' }));

async function askOpenAI(messages) {
  const response = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${OPENAI_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: OPENAI_MODEL, messages, temperature: 0.5 })
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data?.error?.message || 'OpenAI request failed');
  return data.choices?.[0]?.message?.content?.trim() || '';
}

async function askGemini(prompt) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${GEMINI_API_KEY}`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }], generationConfig: { temperature: 0.5 } })
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data?.error?.message || 'Gemini request failed');
  return data.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('').trim() || '';
}

app.post('/api/team-chat', async (req, res) => {
  try {
    if (!OPENAI_API_KEY || !GEMINI_API_KEY) return res.status(503).json({ error: 'На сервере ещё не настроены ключи OpenAI и Gemini.' });
    const message = String(req.body?.message || '').trim();
    if (!message || message.length > 8000) return res.status(400).json({ error: 'Сообщение должно содержать от 1 до 8000 символов.' });

    const first = await askOpenAI([
      { role: 'system', content: 'Ты — ChatGPT в команде AI Duo. Отвечай по-русски, ясно и полезно. Обозначай неопределённость.' },
      { role: 'user', content: message }
    ]);
    const second = await askGemini([
      'Ты — Gemini, второй участник AI Duo. Проверь ответ ChatGPT независимо: найди ошибки, пропуски, риски и полезные дополнения. Не соглашайся автоматически. Отвечай по-русски.',
      `Вопрос пользователя:\n${message}\n\nОтвет ChatGPT:\n${first}\n\nДай краткую критическую оценку и дополнения.`
    ].join('\n\n'));
    const joint = await askOpenAI([
      { role: 'system', content: 'Ты — ChatGPT, ведущий совместного ответа AI Duo. Не скрывай обоснованные разногласия и не выдумывай согласие.' },
      { role: 'user', content: `Вопрос: ${message}\n\nОтвет ChatGPT:\n${first}\n\nПроверка Gemini:\n${second}\n\nСформируй общий итог по-русски. Укажи оставшуюся неопределённость.` }
    ]);
    res.json({ chatgpt: first, gemini: second, joint });
  } catch (error) {
    console.error(error);
    res.status(502).json({ error: error.message || 'AI Duo request failed' });
  }
});

const port = Number(process.env.PORT || 8787);
app.listen(port, () => console.log(`AI Duo backend listening on ${port}`));
