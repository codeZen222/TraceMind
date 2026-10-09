/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        bg: 'var(--tm-bg)',
        surface: 'var(--tm-surface)',
        elevated: 'var(--tm-surface-elevated)',
        border: 'var(--tm-border)',
        'border-strong': 'var(--tm-border-strong)',
        accent: 'var(--tm-accent)',
        'accent-soft': 'var(--tm-accent-soft)',
        'text-1': 'var(--tm-text-1)',
        'text-2': 'var(--tm-text-2)',
        'text-3': 'var(--tm-text-3)',
      },
      fontFamily: {
        sans: 'var(--tm-font-sans)',
        mono: 'var(--tm-font-mono)',
      },
      borderRadius: { DEFAULT: '10px', sm: '6px', lg: '14px' },
    },
  },
  plugins: [],
}
