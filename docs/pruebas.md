# Guía de Pruebas - NexuChat

NexuChat se comunica directamente entre dispositivos mediante Bluetooth / Nearby Connections, por lo que la prueba completa de conectividad requiere **dos o más dispositivos físicos**. Sin embargo, la aplicación incluye un **Modo de Pruebas (Modo DEBUG)** que permite revisar la interfaz y el flujo de mensajería con **un solo dispositivo o en el emulador de Android Studio**, sin necesidad de otro nodo real.

---

## 1. Modo de Pruebas (Modo DEBUG) - Un solo dispositivo / emulador

### 1.1. Cómo activarlo
1. Abrir la app e iniciar sesión (o crear un perfil local).
2. En la pantalla **Radar**, tocar **5 veces seguidas el centro del radar** (sobre el círculo de tu propio nodo).
   * Los toques deben ser rápidos: si pasa más de 1 segundo entre un toque y otro, el contador se reinicia.
3. Aparecerá el mensaje **"🛠 MODO DEBUG"** y el texto bajo el radar cambiará a **"Tu nodo actual (DEBUG)"**.

### 1.2. Qué hace el modo de pruebas
* Detiene la emisión y el escaneo Bluetooth reales.
* El estado del radar cambia a **"Simulando usuarios..."**.
* Aparece en el radar un **nodo simulado llamado "Robot" 🤖**.
* Al tocar el nodo "Robot" se abre el chat **directamente**, sin la solicitud de conexión que exige el modo real.
* En ese chat los mensajes enviados **simulan la entrega**: cambian de estado automáticamente (enviado y luego entregado) y quedan **guardados en el historial local** (Room), igual que en una conversación real.

### 1.3. Cómo desactivarlo
Tocar de nuevo **5 veces seguidas el centro del radar**. Aparecerá **"🌐 MODO REAL"** y el radar volverá a buscar nodos reales.

> **Nota:** El modo de pruebas no se guarda; al cerrar y abrir la app se inicia nuevamente en Modo Real.

### 1.4. Funcionalidades verificables con el modo de pruebas
| Funcionalidad | Cómo verificarla |
|---|---|
| Creación de usuario local | Pantalla de login / registro de perfil. |
| Aura (color de nodo) | Perfil → cambiar color; el radar y la navegación toman el nuevo color. |
| Habilidad / Ocupación | Perfil → editar ocupación. |
| Escaneo de nodos (Colmena) | Radar animado con el nodo simulado "Robot". |
| Interfaz de chat | Tocar "Robot" y enviar mensajes de texto o imágenes. |
| Historial persistente | Enviar mensajes, salir del chat o cerrar la app y volver a entrar: el historial se mantiene. |

---

## 2. Prueba real con dispositivos físicos (Modo Real)

Para probar la conectividad P2P real se necesitan **al menos 2 teléfonos Android físicos** (el emulador no soporta Bluetooth / Nearby Connections).

### 2.1. Requisitos
* Android 8.0 (API 26) o superior.
* Bluetooth y Ubicación activados en ambos dispositivos.
* Aceptar los permisos de Bluetooth, Ubicación y Dispositivos cercanos que solicita la app.

### 2.2. Pasos
1. Instalar la app en ambos dispositivos y crear un perfil distinto en cada uno.
2. Abrir la pantalla **Radar** en los dos: el estado mostrará **"Buscando colmena P2P..."**.
3. Cuando aparezca el otro nodo en el radar, tocarlo y confirmar la solicitud de conexión en el otro dispositivo.
4. Enviar mensajes, imágenes o crear un grupo desde la pantalla de **Chats**.
5. Alejar o apagar Bluetooth en un dispositivo para comprobar que el historial se mantiene.
