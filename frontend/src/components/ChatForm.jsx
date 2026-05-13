import { useState } from "react";

export default function ChatForm({ onSubmit, loading }) {
  const [question, setQuestion] = useState("");
  const [company, setCompany] = useState("");
  const [year, setYear] = useState("2022");

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!question.trim()) {
      return;
    }

    await onSubmit({
      question: question.trim(),
      company: company.trim() || null,
      year: year ? Number(year) : null,
      topK: 5
    });

    setQuestion("");
  };

  return (
    <form className="chat-form" onSubmit={handleSubmit}>
      <div className="filters">
        <label>
          <span>Company</span>
          <input
            value={company}
            onChange={(event) => setCompany(event.target.value)}
            placeholder="Apple Inc."
          />
        </label>

        <label>
          <span>Year</span>
          <select value={year} onChange={(event) => setYear(event.target.value)}>
            <option value="">Any</option>
            <option value="2018">2018</option>
            <option value="2019">2019</option>
            <option value="2020">2020</option>
            <option value="2021">2021</option>
            <option value="2022">2022</option>
          </select>
        </label>
      </div>

      <label className="question">
        <span>Question</span>
        <textarea
          rows="4"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="How did the company describe interest rate or foreign exchange risk?"
        />
      </label>

      <button type="submit" disabled={loading}>
        {loading ? "Analyzing filings..." : "Ask the filings"}
      </button>
    </form>
  );
}
