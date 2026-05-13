import { useState } from "react";
import ChatForm from "./components/ChatForm";
import MessageList from "./components/MessageList";
import SourcePanel from "./components/SourcePanel";
import { sendChat } from "./services/api";

const initialMessages = [
  {
    role: "assistant",
    content:
      "Ask about MD&A or Market Risk disclosures from a specific company and filing year. Responses are grounded only in Section 7 and 7A content."
  }
];

export default function App() {
  const [messages, setMessages] = useState(initialMessages);
  const [sources, setSources] = useState([]);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (payload) => {
    const userMessage = { role: "user", content: payload.question };
    setMessages((current) => [...current, userMessage]);
    setLoading(true);

    try {
      const response = await sendChat(payload);
      setMessages((current) => [
        ...current,
        { role: "assistant", content: response.answer }
      ]);
      setSources(response.sources || []);
    } catch (error) {
      setMessages((current) => [
        ...current,
        {
          role: "assistant",
          content:
            error?.response?.data?.message ||
            "The request could not be completed. Check that the backend, Ollama, and Qdrant are available."
        }
      ]);
      setSources([]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="shell">
      <section className="hero">
        <p className="eyebrow">FilingLens</p>
        <h1>MD&amp;A and Market Risk chatbot with grounded answers.</h1>
        <p className="subtitle">
          Filter by company and filing year, retrieve only Section 7 and 7A
          chunks, and inspect the exact evidence used in each answer.
        </p>
      </section>

      <main className="layout">
        <div className="panel chat-panel">
          <ChatForm onSubmit={handleSubmit} loading={loading} />
          <MessageList messages={messages} loading={loading} />
        </div>
        <aside className="panel sources-panel">
          <SourcePanel sources={sources} />
        </aside>
      </main>
    </div>
  );
}
