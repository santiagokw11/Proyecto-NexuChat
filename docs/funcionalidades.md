# Catálogo de Funcionalidades

## 1. Módulo de Identidad y Perfil
* *Creación de Usuario local:* Registro de un Nickname único y estado/biografía para identificarse en la red sin necesidad de servidores.
* *Aura (Color de Nodo):* Selección de un color representativo (Ej: Pulso violeta) para personalizar la identidad visual del nodo en el radar.
* *Habilidad/Ocupación:* Etiqueta visible para identificar el rol del usuario (ej. Rescatista, Programador, Médico).

## 2. Módulo de Conectividad (Radar P2P)
* *Escaneo de Nodos (Colmena):* Interfaz gráfica que visualiza la búsqueda activa de otros dispositivos Bluetooth cercanos.
* *Modo Nexo vs. Modo Aislado:* Interruptor principal para alternar entre estar visible (emitiendo señal) o invisible (silencioso) en la red local.
* *Funcionamiento en Segundo Plano:* El servicio de emparejamiento Bluetooth se mantiene activo aunque la app esté minimizada.

## 3. Módulo de Mensajería
* *Chats Directos y Grupales:* Comunicación en tiempo real completamente cifrada de extremo a extremo y sin internet.
* *Historial Persistente:* Almacenamiento local para garantizar que los chats no se pierdan al desconectarse.
* *Metadatos de Supervivencia:* Cada mensaje enviado incluye un pequeño indicador con el porcentaje de batería actual del emisor.
* *Alertas del Sistema:* Avisos automáticos dentro del chat cuando un usuario se conecta o desconecta de la colmena.
* *Notificaciones de Proximidad:* Alertas flotantes ("Alias entró en Modo Nexo") cuando un nodo conocido entra en el perímetro de alcance.

## 4. Requerimientos y Restricciones Técnicas

### 4.1. Requerimientos de Hardware

* **Módulo Bluetooth:** Chipset Bluetooth 4.2 o superior compatible con BLE (*Bluetooth Low Energy*) y soporte para emisión en modo periférico (*Advertising*).
* **Alcance Operativo:** Rango nominal de conectividad entre 10 y 30 metros en campo abierto (sujeto a interferencias físicas y de entorno).
* **Consumo Energético:** Eficiencia orientada a baterías móviles; monitoreo pasivo de nivel de batería en dispositivos emisores y receptores.
* **Almacenamiento Físico:** Mínimo de 100 MB de espacio disponible en almacenamiento interno para la base de datos de mensajes, perfiles y archivos temporales.

### 4.2. Requerimientos de Software y Plataforma

* **Sistema Operativo:** Android 8.0 (API nivel 26) o superior para soporte adecuado de servicios en segundo plano y gestión de permisos modernos.
* **Lenguaje y Entorno:** Desarrollada de manera nativa en **Kotlin** bajo Android SDK.
* **Persistencia Local:** Base de datos relacional local basada en **SQLite/Room** para el guardado y consulta de chats, entidades de grupos e historial de nodos.
* **Tareas Asíncronas y Segundo Plano:** Uso de servicios en primer plano (*Foreground Services*) y workers (*WorkManager*) para el mantenimiento de la conexión P2P y la limpieza periódica de datos efímeros.

### 4.3. Restricciones Técnicas

* **Topología Sin Servidor (*Offline-First*):** La aplicación no depende de ninguna API externa, servidor central ni acceso a Internet; toda la lógica de descubrimiento, enrutamiento y entrega se ejecuta directamente entre nodos.
* **Ancho de Banda Limitado:** La transferencia de datos está optimizada para texto plano, metadatos y paquetes ligeros, restringiendo envíos pesados para evitar saturación de la frecuencia de radio.
* **Permisos del Sistema:**
* Permisos de Bluetooth en tiempo de ejecución (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`).
* Permiso de Ubicación aproximada/precisa (requerido por el sistema operativo para el descubrimiento de balizas Bluetooth).
* Exención de optimización de batería opcional para garantizar escaneo continuo en segundo plano.

### 4.4. Seguridad y Cifrado

* **Cifrado de Extremo a Extremo (E2EE):** Cifrado local y en tránsito para que solo los nodos emisores y receptores puedan interpretar los paquetes de datos.
* **Privacidad de Identidad:** No se almacena ni comparte información personal sensible (números telefónicos, correos electrónicos ni identificadores de cuenta externa).