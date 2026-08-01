# 📡 Beacon Chat

> A high-performance, multi-module TCP/UDP client-server chat application built in Java 21.

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/technologies/downloads/#java21)
[![Build Tool](https://img.shields.io/badge/Maven-3.9+-blue.svg)](https://maven.apache.org/)
[![Status](https://img.shields.io/badge/Status-In%20Development-yellow.svg)](#development-status)

---

## 📌 Overview

**Beacon Chat** is a custom network chat system designed for terminal-based real-time communication over TCP and UDP protocols. Built with modularity and concurrent performance in mind, Beacon separates protocol definitions, server state management, and interactive terminal client user interfaces into distinct Maven modules.

Communication uses line-delimited JSON envelopes over TCP sockets for reliable messaging and UDP packets for low-latency features like server discovery and typing status.

---

## 🏗️ Project Architecture

The project is structured as a Maven multi-module architecture:

```text
beacon/
├── beacon-protocol/    # Core protocol vocabulary, JSON envelope definitions, & serialization
├── beacon-server/      # Multi-threaded TCP server, socket handling, & client registry
└── beacon-client/      # CLI client application with interactive commands & real-time reader
```

### Module Responsibilities

- **`beacon-protocol`**: Defines the universal `Message` object schema and `MessageType` enumeration. Handles serialization/deserialization between raw TCP stream text lines and Java objects via Gson.
- **`beacon-server`**: Thread-pooled (`ExecutorService`) TCP server listening for client connections. Handles login verification, broadcasts, private direct messaging, user list tracking, and message routing.
- **`beacon-client`**: Asynchronous command-line interface featuring a dedicated background thread (`TcpServerReader`) for incoming socket data while maintaining responsive user input processing.

---

## ⚡ Features

### 🟢 Currently Implemented
- [x] **Multi-module Maven Structure** (Java 21 source/target).
- [x] **JSON Envelope Protocol** (`Message` with `MessageType`, `sender`, `recipient`, `content`, `timestamp`, `color`).
- [x] **Concurrent Multi-Client Server** using cached thread pooling.
- [x] **User Registration & BCrypt Password Auth** with automatic auto-registration.
- [x] **Embedded H2 Database Persistence** for users and chat history (stored in `~/.beacon/`).
- [x] **Message History on Login** (recent public/private messages rendered on join).
- [x] **Public Broadcast Chat** (`MESSAGE`, `JOINED`, `LEFT`).
- [x] **Private Direct Messaging** (`/msg <username> <message>`).
- [x] **Active User Listing** (`/list`).
- [x] **Message History Search** (`/search <keyword>`).
- [x] **Session Statistics** (`/stats` command for total messages/users stats).
- [x] **Interactive CLI Commands** (`/msg`, `/list`, `/search`, `/stats`, `/quit`).

### 🟡 Roadmap / In Development
- [ ] **Rich Terminal UI**: JLine 3 & JANSI integration for colored output and beacon status panel.
- [ ] **UDP Auto-Discovery**: Automatic local network server broadcast (`DISCOVER_SERVER`).
- [ ] **Typing Indicators**: Real-time typing status over UDP (`TYPING`).
- [ ] **Heartbeat & Latency (RTT)**: Ping/pong diagnostics (`PING` / `PONG`).
- [ ] **CLI Argument Parsing**: Picocli options parsing for server ports and client connection targets.
- [ ] **File Transfers**: P2P/Server-relayed file sharing (`FILE_META`, `FILE_DATA`).

---

## 🛠️ Stack & Dependencies

- **Language**: Java 21
- **Build System**: Maven 3.9+
- **Core Dependencies**:
  - **[Gson](https://github.com/google/gson)** (2.11.0) — JSON serialization/deserialization
  - **[Picocli](https://picocli.info/)** (4.7.6) — CLI option & command parsing
  - **[JLine 3](https://github.com/jline/jline3)** (3.26.3) — Console input handling & line editing
  - **[JANSI](https://fusesource.github.io/jansi/)** (2.4.1) — ANSI escape code support for terminal colors
  - **[H2 Database](https://www.h2database.com/)** (2.2.224) — Embedded message history & user DB
  - **[jBCrypt](https://www.mindrot.org/projects/jBCrypt/)** (0.4) — Password hashing

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
java -cp beacon-server/target/beacon-server-1.0-SNAPSHOT.jar com.beacon.server.BeaconServer 4040
```

### 2. Connect a Client

Open a separate terminal window and launch `BeaconClient`:

```bash
# Connect to localhost:4040
java -cp beacon-client/target/beacon-client-1.0-SNAPSHOT.jar com.beacon.client.BeaconClient localhost 4040
```

---

## 💬 Command Reference

Once connected in the client console, you can use the following slash commands:

| Command | Description | Example |
| :--- | :--- | :--- |
| `/msg <user> <message>` | Send a private message to a specific online user | `/msg alice Hey, are you free?` |
| `/list` | List all users currently logged into the server | `/list` |
| `/search <keyword>` | Search message history for a specific phrase | `/search meeting` |
| `/stats` | Display current chat and server statistics | `/stats` |
| `/quit` | Safely disconnect from the server | `/quit` |

Plain text typed without a `/` prefix is automatically broadcast to all connected users.

---

## 🚧 Development Status

> [!NOTE]
> This repository is under active development. Features are added incrementally. Contributions, suggestions, and feedback are welcome!
