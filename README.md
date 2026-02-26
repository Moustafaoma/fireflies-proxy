# Fireflies Proxy Backend

## Overview

This project is a **Spring Boot Backend Proxy** that integrates with **Fireflies.ai** to automate meeting recording, transcript retrieval, and storage.

Instead of exposing Fireflies APIs directly to the frontend, this service acts as a **secure proxy layer** responsible for:

* Scheduling meetings
* Inviting Fireflies bot automatically
* Receiving webhook events
* Fetching transcripts from Fireflies
* Saving transcripts into the local database

---

## Architecture

Frontend / Client
→ **Fireflies Proxy Backend (This Project)**
→ Fireflies API
→ Webhook Event → Backend → Database

The backend works using an **event-driven integration** based on Fireflies webhooks.

---

## Main Features

### Meeting Scheduling

* Create meetings through backend API
* Automatically invite Fireflies meeting bot
* Store meeting metadata locally

### Webhook Integration

* Secure webhook endpoint
* HMAC signature verification
* Receives **Transcription Completed** events

### Transcript Processing

* Fireflies sends webhook with meetingId
* Backend fetches full transcript via Fireflies API
* Associates transcript with local meeting
* Saves transcript into database

### Proxy Layer

* Hides Fireflies API keys
* Handles rate limiting
* Centralizes API communication
* Adds caching and retry protection

---

## How It Works (Flow)

1. Client schedules a meeting via backend API.
2. Backend calls Fireflies API and invites recording bot.
3. Meeting happens and is recorded by Fireflies.
4. Fireflies sends webhook when transcription is completed.
5. Backend receives webhook event.
6. Backend fetches transcript data from Fireflies API.
7. Transcript is saved into local database.

---

## Tech Stack

* Java 17
* Spring Boot
* Spring Web
* Spring Data JPA / Hibernate
* REST APIs
* GraphQL Integration
* Webhooks
* MySQL / PostgreSQL
* Lombok

---

## Project Structure

```
controller/
service/
repository/
entity/
config/
webhooks/
```

* **Controller** → API endpoints & webhook receiver
* **Service** → Business logic & Fireflies integration
* **Repository** → Database access
* **Entity** → Domain models

---

## Environment Variables

Create environment variables before running:

```
FIREFLIES_API_KEY=your_api_key
FIREFLIES_WEBHOOK_SECRET=your_webhook_secret
```

Never commit secrets to GitHub.

---

## Running the Project

### 1. Clone repository

```
git clone https://github.com/<username>/fireflies-proxy.git
```

### 2. Run application

```
mvn spring-boot:run
```

or

```
java -jar app.jar
```

---

## Webhook Configuration (Fireflies Dashboard)

Webhook URL:

```
https://your-domain/api/webhooks/fireflies
```

Event:

```
Transcription completed
```

---

## API Responsibilities

This backend acts as:

* Integration Layer
* Security Boundary
* Data Persistence Layer
* Automation Service

---

## Future Improvements

* AI Meeting Summaries (LLM Integration)
* Async Processing Queue
* Docker Deployment
* Cloud Deployment (AWS / Alibaba)

---


