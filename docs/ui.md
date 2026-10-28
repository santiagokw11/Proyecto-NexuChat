# Guía de la Interfaz de Usuario (UI) - NexuChat

## 1. Paleta de Colores y Estilo Visual
El diseño de NexuChat prioriza la visibilidad en entornos de baja luminosidad, el bajo consumo de pantalla (OLED/AMOLED) y la legibilidad rápida en situaciones de emergencia o trabajo de campo:
- **Fondo Principal:** `#0D0E12` (Negro carbón profundo / Dark Mode).
- **Fondo Secundario / Tarjetas:** `#1A1C23` (Gris oscuro azulado).
- **Acento Primario (Modo Nexo / Acciones):** `#8A2BE2` (Pulso violeta / Neón).
- **Acento Secundario (Conectividad / Estado activo):** `#00F5D4` (Cian brillante).
- **Alerta / Desconexión:** `#FF4D6D` (Rojo coral).
- **Tipografía:** Sans-serif moderna (Inter / Roboto), limpia y con alto contraste en blanco (`#F8F9FA`) y gris tenue (`#A0A4B8`).

---

## 2. Flujo de Navegación General
La aplicación utiliza un flujo centrado en la inmediatez sin intermediarios de red:
1. **Acceso Inicial (Login Local):** Registro o selección de perfil en el dispositivo.
2. **Navegación Inferior (Bottom Navigation Bar):** Permite alternar instantáneamente entre:
    * **Radar:** Detección de colmena y nodos cercanos.
    * **Chats:** Mensajería directa y salas locales.
    * **Perfil:** Configuración de identidad, aura y métricas de nodo.

---

## 3. Mockups y Diseño de Pantallas

### 3.1. Pantalla de Acceso e Identidad Local (Login)
Vista inicial para la configuración o ingreso del nodo local sin requerir conexión a internet ni servidores externos.

![Mockup de Login e Identidad Local](docs/images/NexuChat%20login.jpeg)

- **Componentes principales:**
    * Campo de entrada para **Nickname único local**.
    * Selector de ocupación/rol inicial (ej. Rescatista, Médico, Operador, Civil).
    * Selector de color de **Aura** (identificador visual del nodo).
    * Botón principal **"Iniciar Nodo" / "Ingresar a la Colmena"**.

---

### 3.2. Radar P2P (Pantalla Principal)
Centro visual de monitoreo y conectividad mediante señales Bluetooth Low Energy (BLE).

![Mockup de Pantalla de Radar P2P](docs/images/mockup_radar.png)

- **Componentes principales:**
    * **Interruptor superior de Estado:** Alternancia entre *Modo Nexo* (visible y emitiendo) y *Modo Aislado* (silencioso/solo recepción).
    * **Radar de Pulso Central:** Ondas concéntricas animadas que representan el rango de alcance (10 a 30 m).
    * **Nodos Detectados:** Iconos flotantes alrededor del radar identificados por su apodo y el color de su Aura.
    * **Acceso rápido al nodo:** Al tocar un nodo se despliega una tarjeta con su rol, distancia estimada y opción de iniciar chat.

---

### 3.3. Bandeja de Conversaciones (Chats)
Lista de canales de comunicación directos y grupales establecidos en la red ad-hoc.

![Mockup de Lista de Chats y Conversación](docs/images/mockup_chats.png)

- **Componentes principales:**
    * **Lista de chats:** Conversaciones activas con indicador de estado (en alcance / fuera de rango).
    * **Metadatos en tiempo real:** Porcentaje de batería del emisor y última hora de sincronización local.
    * **Botón Flotante (FAB):** Permite emitir una solicitud de enlace rápido o crear un chat grupal de emergencia.
    * **Vista de Chat Directo:** Burbujas de chat con indicador de cifrado E2EE y confirmación de recepción entre pares.

---

### 3.4. Perfil y Ajustes del Nodo
Panel de personalización y control de recursos del dispositivo.

![Mockup de Pantalla de Perfil](docs/images/mockup_perfil.png)

- **Componentes principales:**
    * **Identificador visual:** Avatar con el aura seleccionada y alias del usuario.
    * **Gestor de Aura:** Paleta interactiva para cambiar el color con el que otros nodos te ven en el radar.
    * **Métricas de Supervivencia y Red:**
        * Nodos descubiertos en la sesión.
        * Paquetes de datos transmitidos/recibidos.
        * Almacenamiento local utilizado por la base de datos (Room/SQLite).