# 📡 Beacon Chat

> A high-performance, multi-module TCP/UDP client-server chat application built in Java 21 with Clean Architecture.

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/technologies/downloads/#java21)
[![Build Tool](https://img.shields.io/badge/Maven-3.9+-blue.svg)](https://maven.apache.org/)
[![Status](https://img.shields.io/badge/Status-Completed-success.svg)](#features)

---

## 📌 Overview

**Beacon Chat** is a custom network chat system designed for terminal-based real-time communication over TCP and UDP protocols. Built with modularity, Clean Architecture, and concurrent performance in mind, Beacon separates protocol definitions, server state management, and interactive terminal client user interfaces into distinct Maven modules.

Communication uses line-delimited JSON envelopes over TCP sockets for reliable messaging, while UDP is utilized for low-latency server discovery and typing indicators.

---

## 🏗️ Project Architecture

The project is structured as a Maven multi-module architecture:

```text
beacon/
├── beacon-protocol/    # Core protocol vocabulary, JSON envelope definitions, & serialization
├── beacon-server/      # Multi-threaded TCP server, business logic processors, & client registry
└── beacon-client/      # CLI client application with interactive commands & real-time reader
```

### Module Responsibilities

- **`beacon-protocol`**: Defines the universal `Message` object schema and `MessageType` enumeration. Handles serialization/deserialization between raw TCP stream text lines and Java objects via Gson.
- **`beacon-server`**: Thread-pooled (`ExecutorService`) TCP server. Uses Clean Architecture to decouple the raw network socket handling (`ClientHandler`) from the business logic and message routing rules (`ClientCommandProcessor`).
- **`beacon-client`**: Asynchronous command-line interface featuring a dedicated background thread (`TcpServerReader`) for incoming socket data. Utilizes JLine3 for asynchronous prompt rendering (so typing is never interrupted by incoming messages).

---

## ⚡ Features

### 🟢 Core Functionality
- [x] **Multi-module Maven Structure** (Java 21).
- [x] **Clean Architecture** decoupling business rules from network I/O.
- [x] **JSON Envelope Protocol** (`Message` with `MessageType`, `sender`, `recipient`, `content`, `timestamp`).
- [x] **Concurrent Multi-Client Server** using cached thread pooling.
- [x] **User Registration & BCrypt Password Auth** with automatic auto-registration.
- [x] **Embedded H2 Database Persistence** for users and chat history (stored in `~/.beacon/`).
- [x] **Message History on Login** (recent public/private messages rendered on join).
- [x] **Public Broadcast Chat & Channels** (`/join <channel>`).
- [x] **Send files through the chat** (`/send <username> <filepath>`)
- [x] **Interactive CLI Commands** (`/msg`, `/list`, `/listall`, `/search`, `/stats`, `/quit`).

### 🎨 Rich Terminal UI
- [x] **JLine 3 & JANSI Integration** for flawless async console input handling and true 24-bit terminal colors.
- [x] **Typing Indicators**: Real-time status ("Alice and Bob are typing...") broadcast over UDP!
- [x] **UDP Auto-Discovery**: Automatic local network server broadcast to quickly connect without typing IPs.
- [x] **Heartbeat & Latency (RTT)**: Ping/pong diagnostics track live connection latency.
- [x] **Minecraft Chat Formatting**: Full support for classic Minecraft formatting codes (e.g. `&c` for red, `&l` for bold) parsed in real-time.

### 🐣 Easter Eggs
- **`/creeper`**: Broadcasts a flawless True-Color ANSI ASCII art Creeper face.
- **`/herobrine`**: Broadcasts a perfect, 24-bit RGB True Color recreation of Herobrine's face.
- **Classic Minecraft Death Messages**: When a user leaves the server (via `/quit` or timeout), the server announces it using classic Minecraft death messages (e.g., "Username experienced kinetic energy" or "Username tried to swim in lava").

---

## 🛠️ Stack & Dependencies

- **Language**: Java 21
- **Build System**: Maven 3.9+
- **Core Dependencies**:
  - **[Gson](https://github.com/google/gson)** — JSON serialization/deserialization
  - **[JLine 3](https://github.com/jline/jline3)** — Console async input handling & line editing
  - **[JANSI](https://fusesource.github.io/jansi/)** — ANSI escape code support for 24-bit terminal colors
  - **[H2 Database](https://www.h2database.com/)** — Embedded message history & user DB
  - **[jBCrypt](https://www.mindrot.org/projects/jBCrypt/)** — Password hashing

---

## 🚀 Getting Started

### Prerequisites

- **Java Development Kit (JDK)**: Version 21 or higher.
- **Apache Maven**: Version 3.9+ (or use standard Maven wrapper if installed).

### Building the Project

Compile and build all modules from the root directory:

```bash
mvn clean package
```

---

## 💻 Running the Application

### 1. Start the Server

Run the `BeaconServer` module target:

```bash
# Default port: 4040
java -jar beacon-server/target/beacon-server-1.0-SNAPSHOT.jar 4040
```

### 2. Connect a Client

Open a separate terminal window and launch `BeaconClient`:

```bash
# Connect to localhost:4040
java -jar beacon-client/target/beacon-client-1.0-SNAPSHOT.jar localhost 4040
```

*(Note: If you run the client on the same local network, UDP Auto-Discovery will automatically find the server for you!)*

---

## 💬 Command Reference

Once connected in the client console, you can use the following slash commands:

| Command | Description | Example |
| :--- | :--- | :--- |
| `/join <channel>` | Join a different chat room | `/join general` |
| `/msg <user> <msg>` | Send a private message to an online user | `/msg alice Hey!` |
| `/list` | List all users currently logged into the server | `/list` |
| `/listall` | List all registered users ever | `/listall` |
| `/search <keyword>` | Search message history for a specific phrase | `/search meeting` |
| `/send <username> <filepath>` | Send a file to an online user | `/send alice hello.txt` |
| `/stats` | Display current chat and server statistics | `/stats` |
| `/quit` | Safely disconnect from the server | `/quit` |
| `/creeper` | *Easter Egg!* | `/creeper` |
| `/herobrine`| *Easter Egg!* | `/herobrine` |
