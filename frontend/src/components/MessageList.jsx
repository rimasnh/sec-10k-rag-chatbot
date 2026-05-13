function renderInline(text) {
  const segments = text.split(/(\*\*.*?\*\*)/g).filter(Boolean);

  return segments.map((segment, index) => {
    if (segment.startsWith("**") && segment.endsWith("**") && segment.length > 4) {
      return <strong key={`${segment}-${index}`}>{segment.slice(2, -2)}</strong>;
    }
    return segment;
  });
}

function normalizeAssistantContent(content) {
  return content
    .replace(/\*\*\s+\*/g, "**\n* ")
    .replace(/(?<!\n)\*\*(?=[A-Z])/g, "\n\n**")
    .replace(/(?<!\n)\s\*(?=\s*[A-Z])/g, "\n* ");
}

function AssistantMessageBody({ content }) {
  const normalized = normalizeAssistantContent(content);
  const lines = normalized
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const blocks = [];
  let bulletItems = [];

  const flushBullets = () => {
    if (bulletItems.length > 0) {
      blocks.push(
        <ul className="message-bullets" key={`bullets-${blocks.length}`}>
          {bulletItems.map((item, index) => (
            <li key={`${item}-${index}`}>{renderInline(item)}</li>
          ))}
        </ul>
      );
      bulletItems = [];
    }
  };

  lines.forEach((line) => {
    const bulletMatch = line.match(/^[-*]\s+(.*)$/);
    if (bulletMatch) {
      bulletItems.push(bulletMatch[1]);
      return;
    }

    flushBullets();
    blocks.push(
      <p className="message-paragraph" key={`${line}-${blocks.length}`}>
        {renderInline(line)}
      </p>
    );
  });

  flushBullets();

  return <div className="message-body message-body-assistant">{blocks}</div>;
}

function MessageBody({ message }) {
  if (message.role === "assistant") {
    return <AssistantMessageBody content={message.content} />;
  }

  return (
    <div className="message-body">
      <p className="message-paragraph">{message.content}</p>
    </div>
  );
}

export default function MessageList({ messages, loading }) {
  return (
    <div className="message-list">
      {messages.map((message, index) => (
        <article
          key={`${message.role}-${index}`}
          className={`message message-${message.role}`}
        >
          <p className="message-role">{message.role}</p>
          <MessageBody message={message} />
        </article>
      ))}
      {loading ? (
        <article className="message message-assistant pending">
          <p className="message-role">assistant</p>
          <div className="message-body">
            <p className="message-paragraph">
              Searching vector evidence and composing a grounded response.
            </p>
          </div>
        </article>
      ) : null}
    </div>
  );
}
