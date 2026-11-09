# NexuChat

dsNexuChat es una aplicación de mensajería instantánea P2P (Peer-to-Peer) descentralizada para Android que usa **Bluetooth / Google Nearby Connections** para establecer conexiones directas entre dispositivos cercanos y formar una red mesh. No requiere internet, Wi-Fi con router ni servidores centrales: cada teléfono es un nodo, y los mensajes pueden saltar de nodo en nodo (hasta 4 saltos) para llegar a destinos fuera de alcance directo.

**Autores:** David Santiago Rodríguez Barrero, Jeison Javier Cadena Celis, Nelson Javier Velásquez

---

## Tabla de contenido

- [Capturas de pantalla](#capturas-de-pantalla)
- [Funcionalidades](#funcionalidades)
- [Arquitectura y stack técnico](#arquitectura-y-stack-técnico)
- [Permisos que solicita la app](#permisos-que-solicita-la-app)
- [Cómo probar](#cómo-probar)
- [Limitaciones conocidas](#limitaciones-conocidas)
- [Documentación adicional](#documentación-adicional)

---

## Capturas de pantalla

> Capturadas en un dispositivo físico real (Samsung Galaxy A55, `SM-A556B`) corriendo la build actual.

<table align="center">
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/radar.png" width="220"/><br/>
      <sub><b>Radar P2P</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/chats_list.png" width="220"/><br/>
      <sub><b>Lista de chats</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/chat_conversation.png" width="220"/><br/>
      <sub><b>Conversación</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/profile.png" width="220"/><br/>
      <sub><b>Perfil</b></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/settings.png" width="220"/><br/>
      <sub><b>Ajustes</b></sub>
    </td>
    <td width="33%"></td>
  </tr>
</table>

---

## Funcionalidades

Estado real verificado en el código, no aspiracional. Basado en el catálogo completo de [`docs/funcionalidades.md`](docs/funcionalidades.md).

### Identidad y Perfil
| Funcionalidad | Estado |
|---|---|
| Creación de usuario local (nickname, sin servidor) | ✅ Implementado |
| Multicuenta en el mismo dispositivo | ✅ Implementado |
| Aura (color de nodo personalizable) | ✅ Implementado |
| Foto de perfil / avatar, sincronizado entre nodos conectados | ✅ Implementado |
| Habilidad/ocupación, bio, emoji de estado | ✅ Implementado |
| Login biométrico (huella/rostro) opcional | ✅ Implementado |

### Conectividad (Radar P2P)
| Funcionalidad | Estado |
|---|---|
| Escaneo y descubrimiento de nodos cercanos (radar animado) | ✅ Implementado |
| Conexión P2P con confirmación manual del receptor | ✅ Implementado |
| Enrutamiento mesh con reenvío entre nodos (TTL de 4 saltos) | ✅ Implementado |
| Modo Fantasma (dejar de emitir señal, solo escuchar) | ✅ Implementado |
| Reintento automático de mensajes pendientes al reconectar | ✅ Implementado |
| Servicio real en segundo plano (seguir recibiendo con la app minimizada) | ✅ Implementado — la app sigue escuchando y conectándose con otros nodos aunque la minimices; si Android llega a cerrarla por memoria, se reactiva sola la próxima vez que el sistema le da una oportunidad |
| Notificación de proximidad ("Fulano entró en rango") | ✅ Implementado |

### Mensajería
| Funcionalidad | Estado |
|---|---|
| Chats 1 a 1 | ✅ Implementado |
| Chats grupales (crear, añadir/quitar miembros, avatar de grupo) | ✅ Implementado |
| Mensajes efímeros (autodestrucción por tiempo) y grupos efímeros | ✅ Implementado |
| Envío de imágenes, video, documentos y notas de voz | ✅ Implementado |
| Confirmaciones de entrega y lectura (check gris/azul) | ✅ Implementado |
| Historial persistente local (Room/SQLite) | ✅ Implementado |
| "Zumbido" (nudge) con vibración | ✅ Implementado |
| Reenvío de mensajes entre chats | ✅ Implementado |
| Bóveda multimedia por chat (fotos, videos, documentos, enlaces) | ✅ Implementado |
| Indicador de batería del emisor en cada mensaje | ✅ Implementado |
| Alertas de conexión/desconexión dentro del chat | ✅ Implementado |
| Cifrado de extremo a extremo real | ✅ Implementado — ver [Limitaciones conocidas](#limitaciones-conocidas) |

---

## Arquitectura y stack técnico

- **Lenguaje:** todo el proyecto está escrito en Kotlin puro. No se usa Flutter, React Native ni ningún otro framework multiplataforma.
- **Qué celulares la pueden correr:** desde Android 8.0 en adelante (lanzado en 2017), así que prácticamente cualquier equipo actual la soporta.
- **Dónde se guardan los mensajes:** en el propio teléfono, con una base de datos local (Room, sobre SQLite). No hay un servidor en internet guardando nada.
- **Cómo se conectan los teléfonos entre sí:** con Google Nearby Connections, la misma tecnología que usan apps como Google Files para compartir archivos sin internet. Se eligió el modo que permite que varios dispositivos estén conectados entre sí al mismo tiempo (no solo de uno a uno), que es lo que hace posible armar la red mesh.
- **Cómo viaja la información:** cada mensaje que salta de teléfono en teléfono lleva una pequeña "etiqueta" que dice quién lo manda, para quién es y cuántos saltos más puede dar; el contenido del mensaje en sí va cifrado de extremo a extremo, así que ningún teléfono que solo esté retransmitiendo puede leerlo (más detalle en la tabla de Funcionalidades).
- **Videos y audios:** se reproducen con AndroidX Media3/ExoPlayer, el reproductor que usa Android por debajo de apps como YouTube.
- **Huella/rostro al entrar:** con AndroidX Biometric, la librería oficial de Android para biometría.
- **Pantallas:** hechas con el sistema de vistas tradicional de Android (View Binding), no con Jetpack Compose.

### Cómo está organizado el código (`app/src/main/java/com/offline/nexu/`)
```
data/           Entidades de la base de datos, consultas, preferencias guardadas
data/network/   Todo lo que envía y recibe por la red P2P (NexuMeshManager, el cifrado)
ui/home/        El Radar y el estado de quién está conectado en este momento
ui/chat/        Lista de chats, conversación, grupos, galería de multimedia
ui/profile/     Perfil del usuario
ui/settings/    Ajustes, privacidad, personalización, tema
ui/login/       Registro, inicio de sesión y multicuenta
notifications/  Qué pasa cuando tocas "Aceptar"/"Rechazar" en una notificación de conexión
utils/          Cosas chiquitas que se usan en varias partes (tema, generador de IDs)
```

---

## Permisos que solicita la app

Cada permiso se pide en tiempo de ejecución justo antes de usarse (no todos al abrir la app):

| Permiso | Para qué | Cuándo se pide |
|---|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Requisito del sistema operativo para escanear Bluetooth/BLE | Al abrir la pantalla Radar |
| `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` (Android 12+) | Descubrir y conectar con nodos cercanos | Al abrir la pantalla Radar |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Nearby Connections usa Wi-Fi Direct/Aware además de Bluetooth | Al abrir la pantalla Radar |
| `POST_NOTIFICATIONS` (Android 13+) | Mostrar notificaciones de mensajes, solicitudes de conexión, grupos | Al abrir la pantalla Radar |
| `CAMERA` | Tomar foto para enviar en el chat | Al tocar el botón de cámara en un chat |
| `RECORD_AUDIO` | Grabar notas de voz | Al mantener presionado el botón de enviar |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` (según versión) | Adjuntar imágenes/videos de la galería | Al abrir el selector de galería |
| `VIBRATE` | Vibración al recibir zumbidos/notificaciones | No requiere diálogo (normal) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Mantener la conexión P2P activa en segundo plano | No requiere diálogo (normal) |

---

## Cómo probar

La app incluye un **Modo de Pruebas (DEBUG)** que funciona con un solo dispositivo o en el emulador, sin necesitar un segundo nodo real:

1. En la pantalla **Radar**, toca **5 veces seguidas el centro del radar** (tu propio nodo).
2. Aparecerá **"🛠 MODO DEBUG"** y un nodo simulado **"Robot" 🤖**.
3. Toca a "Robot" para abrir un chat simulado y probar mensajes e historial.
4. Toca otra vez 5 veces el centro del radar para volver a **"🌐 MODO REAL"**.

Guía completa, incluyendo la prueba con dos dispositivos reales: [`docs/pruebas.md`](docs/pruebas.md).

---

## Limitaciones conocidas

- **Alcance del cifrado E2E.** El contenido de los mensajes (texto, comandos de sistema, encabezados de archivo) se cifra con AES-256-GCM usando una clave derivada por ECDH (curva P-256) entre el emisor y el destinatario real; un nodo que solo reenvía el paquete por la malla no puede leerlo, solo reenviarlo. La llave pública se intercambia automáticamente al conectar con un vecino directo, así que funciona de inmediato en el escenario probado (dos dispositivos en alcance). Si un mensaje se envía antes de completarse ese intercambio (poco probable, es casi instantáneo), viaja marcado explícitamente como no cifrado en vez de fallar. Los bytes crudos de imágenes/video/audio/documentos no se cifran a nivel de aplicación (viajan tal cual sobre el transporte ya cifrado de Nearby Connections). El interruptor "Cifrado de base de datos" en Ajustes → Privacidad es independiente de esto: solo exige biometría para abrir la app, no cifra el contenido de la base de datos en disco.
- **El alcance real depende del hardware y el entorno** (paredes, interferencia), normalmente 10-30 m en campo abierto.
- El servicio en segundo plano puede ser detenido por el sistema operativo si el usuario desliza la app fuera de "Recientes" en fabricantes con gestión agresiva de batería (p. ej. Samsung); `START_STICKY` y el arranque desde `NexuApplication` lo recuperan en la mayoría de los casos, pero no hay garantía absoluta de Android.

---

## Documentación adicional

- [Ideas iniciales y justificación del proyecto](docs/ideas.md)
- [Catálogo completo de funcionalidades y requerimientos técnicos](docs/funcionalidades.md)
- [Diseño de interfaz (mockups)](docs/ui.md)
- [Guía de pruebas (Modo DEBUG y dispositivos reales)](docs/pruebas.md)
