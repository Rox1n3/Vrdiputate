@echo off
cd /d %~dp0

echo === Step 1: npm install ===
if not exist node_modules (
    npm install
)

echo === Step 2: prisma db push ===
npx prisma db push --skip-generate

echo === Step 3: kill port 3000 ===
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":3000 "') do taskkill /F /PID %%a >nul 2>&1

echo === Starting server ===
echo Emulator URL: http://10.0.2.2:3000/
echo.

npm run dev

pause
