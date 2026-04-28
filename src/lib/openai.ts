import OpenAI from 'openai';

function readApiKey() {
  return (
    process.env.OPENAI_API_KEY?.trim() ||
    process.env.OPENAI_KEY?.trim() ||
    process.env.API_KEY?.trim() ||
    ''
  );
}

export function getOpenAIClient() {
  const apiKey = readApiKey();
  if (!apiKey) {
    throw new Error('Не задан OPENAI_API_KEY (или OPENAI_KEY/API_KEY)');
  }

  return new OpenAI({ apiKey });
}

export function getOpenAIModel() {
  return process.env.OPENAI_MODEL?.trim() || 'gpt-4o-mini';
}
