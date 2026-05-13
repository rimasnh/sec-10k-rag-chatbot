import axios from "axios";

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api"
});

export async function sendChat(payload) {
  const response = await api.post("/chat", payload);
  return response.data;
}
