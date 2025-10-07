# Repository Guidelines

## Reference Docs
- `doc.md`: arquitectura y flujos para la app Ionic + Capacitor; actualizalo cuando cambie el bridge o los permisos nativos.
- `TASK.md`: plan operativo vigente con pasos pendientes (Android lifecycle, ajustes iOS, pruebas, validacion E2E, documentacion final).

## Project Structure & Module Organization
- El codigo vive en `ionic-quiz-helper/`; `src/` contiene pantallas Ionic, bridge JS y wrappers de plugins.
- Mantener `src/bridge/quiz-js.ts` como unica fuente de los scripts inyectados; versiona cambios junto a cualquier ajuste en la API `/solve`.
- Ubicar wrappers Capacitor en `src/capacitor-plugins/` y asegurarse de importarlos una sola vez en `src/main.tsx`.
- El codigo nativo custom va en `android/app/src/main/java/com/example/quizhelper/` y `ios/App/App/`; agrupa clases bajo el prefijo `NativeWebView`.

## Build, Test, and Development Commands
- `npm install` para dependencias iniciales.
- `npm run dev` abre el servidor Vite; usa `ionic cap run` cuando te conectes a dispositivos.
- `npm run build` seguido de `npx cap sync` antes de tocar proyectos nativos.
- `npm run test` ejecuta Jest; agrega `-- --watch` para desarrollo.
- `npx cap open android` / `npx cap open ios` lanzan Android Studio y Xcode respectivamente.

## Coding Style & Naming Conventions
- TypeScript con 2 espacios, sin semicolons extra; ejecuta Prettier o ESLint antes de subir cambios (`npm run lint`).
- Componentes React y clases de plugin en PascalCase; hooks en camelCase; constantes globales en SCREAMING_SNAKE_CASE solo si son compartidas.
- Mantener strings inyectados en template literals con escapes claros y comentarios solo donde expliquen decisiones del DOM.

## Testing Guidelines
- Usa Jest + Testing Library para UI; mockea `NativeWebView` y `@capacitor-community/http`.
- Coloca pruebas en `src/__tests__/` con sufijo `.test.ts(x)` y cubre estados de exito, fallo y ausencia del plugin.
- Apunta a >= 80% de cobertura; documenta excepciones en `TASK.md`.

## Commit & Pull Request Guidelines
- Sigue Conventional Commits (`feat:`, `fix:`, `chore:`) con sujetos <= 72 caracteres.
- Cada PR debe describir alcance, comandos ejecutados (`npm run lint`, `npm run test`, `npx cap sync`), y adjuntar capturas/logs cuando cambie el flujo del WebView.
- Solicita al menos una revision y vincula issues de backend o configuracion relevantes.

## Native Plugin Notes
- Android: agrega manejo de back navigation, limpieza en `onPause/onDestroy` y validaciones de origen antes de cargar URLs.
- iOS: garantiza que el plugin se inicialice en `AppDelegate` y expone un metodo para cerrar el WebView; documenta requisitos de App Transport Security.
- Refleja cualquier cambio nativo en `doc.md` y en guias de pruebas manuales.
