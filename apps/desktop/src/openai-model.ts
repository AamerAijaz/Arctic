import type { ConversationTurn, JsonObject, Model, ModelCall, ModelResult, ToolRequest } from "./host";

type ChatCompletion = {
  choices?: Array<{
    message?: {
      content?: unknown;
      tool_calls?: unknown;
    };
  }>;
};

type OpenAiMessage =
  | { role: "system" | "user" | "assistant"; content: string }
  | {
      role: "assistant";
      content: null;
      tool_calls: Array<{ id: string; type: "function"; function: { name: string; arguments: string } }>;
    }
  | { role: "tool"; tool_call_id: string; content: string };

function isPlainObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isToolTurn(message: ConversationTurn): message is Extract<ConversationTurn, { role: "tool" }> {
  return message.role === "tool";
}

function toOpenAiMessages(instructions: string, turns: ConversationTurn[]): OpenAiMessage[] {
  const messages: OpenAiMessage[] = [{ role: "system", content: instructions }];
  let index = 0;
  while (index < turns.length) {
    const turn = turns[index];
    if (isToolTurn(turn)) {
      const run: Array<Extract<ConversationTurn, { role: "tool" }>> = [];
      while (index < turns.length && isToolTurn(turns[index])) {
        run.push(turns[index] as Extract<ConversationTurn, { role: "tool" }>);
        index += 1;
      }
      messages.push({
        role: "assistant",
        content: null,
        tool_calls: run.map((tool) => ({
          id: tool.callId,
          type: "function",
          function: { name: tool.name, arguments: JSON.stringify(tool.arguments ?? {}) },
        })),
      });
      for (const tool of run) {
        messages.push({ role: "tool", tool_call_id: tool.callId, content: tool.text });
      }
      continue;
    }
    if (turn.role === "person") {
      messages.push({ role: "user", content: turn.text });
    } else {
      messages.push({ role: "assistant", content: turn.text });
    }
    index += 1;
  }
  return messages;
}

function parseArguments(raw: unknown): JsonObject | null {
  if (typeof raw !== "string") {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    return isPlainObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function parseToolCalls(toolCalls: unknown[]): ToolRequest[] {
  return toolCalls.map((item) => {
    const record = isPlainObject(item) ? item : {};
    const fn = isPlainObject(record.function) ? record.function : {};
    return {
      id: typeof record.id === "string" ? record.id : "",
      name: typeof fn.name === "string" ? fn.name : "",
      arguments: parseArguments(fn.arguments),
    };
  });
}

export class OpenAiModel implements Model {
  async complete(call: ModelCall): Promise<ModelResult> {
    const instructions = typeof call.instructions === "string" ? call.instructions : "";
    const tools = Array.isArray(call.tools) ? call.tools : [];
    const turns = Array.isArray(call.messages) ? call.messages : [];
    const root = call.apiRoot.replace(/\/+$/, "");
    const payload: {
      model: string;
      messages: OpenAiMessage[];
      tools?: Array<{
        type: "function";
        function: { name: string; description: string; parameters: Record<string, unknown> };
      }>;
    } = {
      model: call.modelName,
      messages: toOpenAiMessages(instructions, turns),
    };
    if (tools.length > 0) {
      payload.tools = tools.map((tool) => ({
        type: "function",
        function: {
          name: tool.name,
          description: tool.description,
          parameters: tool.inputSchema,
        },
      }));
    }
    let response: Response;
    try {
      response = await fetch(`${root}/chat/completions`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${call.credential}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
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
    const toolCalls = message?.tool_calls;
    if (Array.isArray(toolCalls) && toolCalls.length > 0) {
      return {
        kind: "tool_calls",
        text: typeof message?.content === "string" ? message.content : "",
        calls: parseToolCalls(toolCalls),
      };
    }
    if (typeof message?.content !== "string") {
      return { kind: "unreachable" };
    }
    return { kind: "assistant", text: message.content };
  }
}
