// ESLint flat config for the Black Box SolidJS frontend.
//
// Layers, in order: ESLint core recommended, typescript-eslint recommended (syntax-only, no type
// information, so it stays fast and needs no tsconfig coverage of tests/), the SolidJS plugin's
// TypeScript preset (reactivity tracking, no-destructure, prefer-for, jsx-no-undef, ...), and
// eslint-config-prettier last so no formatting rule fights Prettier. Formatting is Prettier's job;
// this file is about correctness.
import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import eslintConfigPrettier from "eslint-config-prettier/flat";
import solid from "eslint-plugin-solid";
import globals from "globals";
import tseslint from "typescript-eslint";

export default defineConfig([
  globalIgnores([
    "node_modules/",
    "dist/",
    "test-results/",
    "playwright-report/",
    "public/",
    "../src/main/resources/static/",
  ]),
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    ...solid.configs["flat/typescript"],
  },
  {
    // Solid assigns `let el: HTMLElement | undefined` through the JSX `ref={el}` binding at compile
    // time, which core ESLint cannot see. The rule stays on for plain .ts modules.
    files: ["**/*.tsx"],
    rules: {
      "no-unassigned-vars": "off",
    },
  },
  {
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      // Unused values are errors, but an underscore prefix marks an intentional placeholder.
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          destructuredArrayIgnorePattern: "^_",
        },
      ],
    },
  },
  {
    // Vitest specs and setup: globals: true is enabled in vitest.config.ts.
    files: ["src/**/*.test.{ts,tsx}", "src/test/**"],
    languageOptions: {
      globals: {
        ...globals.vitest,
      },
    },
    rules: {
      // Specs read signals and mutate router params outside tracked scopes on purpose; the
      // reactivity rule is only meaningful for component and store code.
      "solid/reactivity": "off",
    },
  },
  eslintConfigPrettier,
]);
