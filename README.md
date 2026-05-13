# FilingLens

Run the FilingLens UI locally and ask questions about SEC 10-K filings from:

- Section 7: Management's Discussion and Analysis
- Section 7A: Quantitative and Qualitative Disclosures About Market Risk

## What You Need

- Java 17
- Maven 3.9+
- Node.js 20+
- Docker and Docker Compose
- The parquet data file(s) in [`data/`](/Users/rimasinha/Downloads/project/FilingLens/data)

## Run The App

1. Start Qdrant and Ollama:

```bash
docker compose up -d qdrant ollama
```

2. Pull the required Ollama models if they are not already available:

```bash
ollama pull llama3
ollama pull nomic-embed-text
```

3. Start the backend:

```bash
cd backend
mvn spring-boot:run
```

4. In a new terminal, load the filing data:

```bash
curl -X POST http://localhost:8080/api/admin/ingest
```

5. In a new terminal, start the frontend:

```bash
cd frontend
npm install
npm run dev
```

6. Open the UI:

```text
http://localhost:5173
```

## Use The UI

1. Enter a company name: `NortonLifeLock Inc.`
2. Select a filing year: `2022`
3. Ask a question about the filing.
4. Review the answer and expand the retrieved evidence to inspect the source text.

### Sample Questions

- `How many users does NortonLifeLock empower through its Cyber Safety platform?`
- `When did merger with Avast happen?`
- `What does this passage say about cyber safety platform reach?`
- `What changed year over year?`
- `What is mentioned in the Cooperation Agreement?`
- `Summarize the cooperative agreement with Avast.`

## If Something Does Not Work

- If the UI does not load, make sure the frontend is running on `http://localhost:5173`.
- If chat requests fail, make sure the backend is running on `http://localhost:8080`.
- If answers are empty, run the ingestion step again and confirm the parquet file exists in [`data/`](/FilingLens/data).
- If model calls fail, make sure Ollama is running and both models were pulled successfully.
