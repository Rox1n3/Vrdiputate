import { PrismaClient } from '@prisma/client';

const globalForPrisma = global as unknown as { prisma: PrismaClient | undefined };

function createPrismaClient(): PrismaClient {
  if (process.env.TURSO_DATABASE_URL) {
    // Продакшен: подключение к Turso через LibSQL адаптер
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { createClient } = require('@libsql/client');
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { PrismaLibSQL } = require('@prisma/adapter-libsql');

    const libsql = createClient({
      url: process.env.TURSO_DATABASE_URL,
      authToken: process.env.TURSO_AUTH_TOKEN ?? '',
    });

    const adapter = new PrismaLibSQL(libsql);
    return new PrismaClient({ adapter } as Parameters<typeof PrismaClient>[0] & { adapter: unknown });
  }

  // Локальная разработка: SQLite файл
  return new PrismaClient({ log: ['warn', 'error'] });
}

export const prisma = globalForPrisma.prisma ?? createPrismaClient();

if (process.env.NODE_ENV !== 'production') globalForPrisma.prisma = prisma;
