import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: "class",
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}"
  ],
  theme: {
    extend: {
      colors: {
        steppe: "#14B8A6", // бирюзовый (основной)
        dune: "#f5f3eb",   // песочный фон
        sky: "#0d9488",    // тёмный бирюзовый (hover)
        gold: "#f2c94c"    // золотой акцент
      }
    }
  },
  plugins: []
};

export default config;
