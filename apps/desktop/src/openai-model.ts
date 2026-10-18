import type { Model, ModelCall, ModelResult } from "./host";

type ChatCompletion = {
  choices?: Array<{
    message?: {
      content?: unknown;
      tool_calls?: unknown[];
    };
  }>;
};

export class OpenAiModel implements Model {
  async complete(call: ModelCall): Promise<ModelResult> {
    const root = call.apiRoot.replace(/\/+$/, "");
    let response: Response;
    try {
      response = await fetch(`${root}/chat/completions`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${call.credential}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model: call.modelName,
          messages: call.messages.map((message) => ({
            role: message.role === "person" ? "user" : "assistant",
            content: message.text,
          })),
        }),
      });
    } catch {
      return { kind: "unreachable" };
    }
    if (response.status === 401) {
      return { kind: "rejected" };
    }
    if (!response.ok) {
      return { kind: "unreachable" };
    }
    let body: ChatCompletion;
    try {
      body = (await response.json()) as ChatCompletion;
    } catch {
      return { kind: "unreachable" };
    }
    const message = body.choices?.[0]?.message;
    if (message?.tool_calls && message.tool_calls.length > 0) {
      return { kind: "tool_call" };
    }
    if (typeof message?.content !== "string") {
      return { kind: "unreachable" };
    }
    return { kind: "assistant", text: message.content };
  }
}
