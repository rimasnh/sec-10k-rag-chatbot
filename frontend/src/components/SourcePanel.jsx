import { useState } from "react";

function SourceCard({ source, index }) {
  const [expanded, setExpanded] = useState(false);
  const relevance = typeof source.relevance === "number"
    ? source.relevance.toFixed(2)
    : null;
  const chunkLabel = source.chunkId
    ? `Chunk ${source.chunkId.slice(0, 8)}`
    : `Chunk ${index + 1}`;

  return (
    <article className="source-card">
      <div className="source-meta">
        <span className="source-chip">{source.year || "Unknown year"}</span>
        <span className="source-chip">{source.company || "Unknown company"}</span>
        <span className="source-chip">Item {source.section || "N/A"}</span>
        <span className="source-chip">{chunkLabel}</span>
      </div>
      <div className="source-content">
        <p className={`source-text ${expanded ? "source-text-expanded" : ""}`}>
          {source.text}
        </p>
      </div>
      <div className="source-footer">
        <p className="source-origin">
          Source: Qdrant
          {relevance ? ` • Relevance ${relevance}` : ""}
        </p>
        <p className="source-file">
          {source.sourceFile}
          {source.filingDate ? ` • ${source.filingDate}` : ""}
        </p>
      </div>
      {source.text ? (
        <button
          type="button"
          className="source-toggle"
          onClick={() => setExpanded((current) => !current)}
          aria-expanded={expanded}
        >
          {expanded ? "Show less" : "Show full evidence"}
        </button>
      ) : null}
    </article>
  );
}

export default function SourcePanel({ sources, telemetry }) {
  const [copied, setCopied] = useState(false);

  const copyToClipboard = async (text) => {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return;
    }

    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "true");
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    textarea.style.pointerEvents = "none";
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();

    const copiedSuccessfully = document.execCommand("copy");
    document.body.removeChild(textarea);

    if (!copiedSuccessfully) {
      throw new Error("Clipboard copy failed");
    }
  };

  const handleCopy = async () => {
    if (sources.length === 0) {
      return;
    }

    const payload = sources.map((source, index) => [
      `[${source.year || "Unknown year"}] [${source.company || "Unknown company"}] [Item ${source.section || "N/A"}] [${source.chunkId ? `Chunk ${source.chunkId.slice(0, 8)}` : `Chunk ${index + 1}`}]`,
      source.text || "",
      `Source: Qdrant${typeof source.relevance === "number" ? ` • Relevance ${source.relevance.toFixed(2)}` : ""}`,
      source.sourceFile || ""
    ].filter(Boolean).join("\n")).join("\n\n---\n\n");

    try {
      await copyToClipboard(payload);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch (error) {
      console.error("Failed to copy sources", error);
    }
  };

  return (
    <div className="source-panel">
      <div className="source-header">
        <div>
          <h2>Retrieved Evidence</h2>
          <p>Source chunks returned from Qdrant for the current answer.</p>
        </div>
        <button
          type="button"
          className="source-copy"
          onClick={handleCopy}
          disabled={sources.length === 0}
        >
          {copied ? "Copied" : "Copy"}
        </button>
      </div>

      {telemetry ? (
        <section className="telemetry-card" aria-label="Request telemetry">
          <div className="telemetry-metric">
            <span className="telemetry-label">Request latency</span>
            <strong>{telemetry.requestLatencyMs} ms</strong>
          </div>
          <div className="telemetry-metric">
            <span className="telemetry-label">Retrieval latency</span>
            <strong>{telemetry.retrievalLatencyMs} ms</strong>
          </div>
          <div className="telemetry-metric">
            <span className="telemetry-label">LLM latency</span>
            <strong>{telemetry.llmLatencyMs} ms</strong>
          </div>
        </section>
      ) : null}

      {sources.length === 0 ? (
        <div className="empty-state">
          <p>No source snippets yet.</p>
        </div>
      ) : (
        sources.map((source, index) => (
          <SourceCard
            key={`${source.sourceFile}-${index}`}
            source={source}
            index={index}
          />
        ))
      )}
    </div>
  );
}
