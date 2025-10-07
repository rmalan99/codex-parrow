# Task Plan - Ionic Quiz Helper

## Contexto rapido
- doc.md define arquitectura base (pantalla Home, bridge JS, plugins nativos) ya implementada.
- AGENTS.md resume convenciones de contribucion y comandos.
- Flujo principal abre quiz en WebView, consulta `/solve` y resalta opciones, pero faltan validaciones, pruebas y documentacion final.
- Riesgos actuales: manejo incompleto del ciclo de vida del WebView Android, registro basico en iOS, ausencia de pruebas y sin verificacion en dispositivo real.

## Lista de acciones paso a paso
1. **Endurecer plugin Android**
   - Agregar manejo de `onDestroy` y `onPause` para limpiar WebView y evitar fugas.
   - Interceptar boton back nativo y reenviar al WebView antes de cerrar la vista.
   - Documentar nuevos metodos/callbacks en doc.md.
2. **Alinear plugin iOS**
   - Revisar `AppDelegate.swift` e integrar cualquier configuracion requerida (por ejemplo registrar plugin o configurar `CAPBridge`).
   - Exponer helpers para descartar el WebView y validar rotacion y seguridad de contenido.
   - Anotar ajustes y riesgos (por ejemplo permisos de ATS) en doc.md.
3. **Cobertura automatizada frontend**
   - Crear pruebas Jest para `Home.tsx` validando ramas de exito, error, plugin ausente y render de estados.
   - Crear mocks para `NativeWebView` y `@capacitor-community/http` en `__tests__/helpers`.
   - Incluir tests de smoke para `bridge/quiz-js.ts` verificando selectores generados.
4. **Validacion end-to-end**
   - Definir escenarios de prueba manual en Android e iOS (incluir datos de API fake en `.env.example`).
   - Ejecutar `npm run build` + `npx cap sync` y abrir proyectos nativos (`npx cap open android`, `npx cap open ios`).
   - Capturar evidencias (logs, capturas) y documentarlas en doc.md y TASK.md.
5. **Cierre de documentacion**
   - Actualizar doc.md con cambios nativos, configuracion de entorno, matrices de prueba y pendientes.
   - Revisar AGENTS.md para reflejar nuevas convenciones o comandos.
   - Proponer siguientes pasos (por ejemplo automatizar pruebas en CI y soporte de multiples dominios).

## Referencias rapidas
- Comandos utiles: `npm run build`, `npm run test`, `npm run lint`, `npx cap sync`, `npx cap open android`, `npx cap open ios`.
- Variables de entorno: `VITE_API_BASE` para definir endpoint real de `/solve`.
- Documentar cada cambio en commits siguiendo Conventional Commits (ej. `feat: add back navigation handling`).
- Mantener cobertura >= 80% y registrar excepciones justificadas en esta TASK.
