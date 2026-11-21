package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const deepSeekBase = "https://api.deepseek.com"

type ModelConfig struct {
	BaseURL, Name, Key string
	active             bool
}

func ModelFromEnv() (ModelConfig, error) {
	base := strings.TrimRight(os.Getenv("TIMEPIN_AI_BASE_URL"), "/")
	custom := base != "" && base != deepSeekBase
	if base == "" {
		base = deepSeekBase
	}
	u, e := url.Parse(base)
	if e != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "https" && u.Scheme != "http") {
		return ModelConfig{}, errors.New("invalid TIMEPIN_AI_BASE_URL")
	}
	key := os.Getenv("TIMEPIN_AI_API_KEY")
	if key == "" && !custom {
		key = os.Getenv("DEEPSEEK_API_KEY")
	}
	name := os.Getenv("TIMEPIN_AI_MODEL")
	if name == "" {
		name = "deepseek-flash"
	}
	return ModelConfig{base, name, key, key != "" || custom}, nil
}
func (m ModelConfig) Enabled() bool { return m.active }

type modelCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}
type modelMessage struct {
	Role      string      `json:"role"`
	Content   string      `json:"content"`
	Calls     []modelCall `json:"tool_calls,omitempty"`
	Reasoning string      `json:"reasoning_content,omitempty"`
	CallID    string      `json:"tool_call_id,omitempty"`
}

var modelHTTP = &http.Client{Timeout: 40 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}

func (m ModelConfig) complete(ctx context.Context, messages []modelMessage, tools []any, stream *chatStream) (modelMessage, error) {
	payload := map[string]any{"model": m.Name, "messages": messages, "tools": tools, "stream": stream != nil}
	if m.BaseURL == deepSeekBase {
		payload["thinking"] = map[string]string{"type": "disabled"}
	}
	b, e := json.Marshal(payload)
	if e != nil {
		return modelMessage{}, e
	}
	req, e := http.NewRequestWithContext(ctx, "POST", m.BaseURL+"/chat/completions", bytes.NewReader(b))
	if e != nil {
		return modelMessage{}, e
	}
	req.Header.Set("Content-Type", "application/json")
	if m.Key != "" {
		req.Header.Set("Authorization", "Bearer "+m.Key)
	}
	resp, e := modelHTTP.Do(req)
	if e != nil {
		return modelMessage{}, problem{502, "无法连接实例模型或请求超时"}
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return modelMessage{}, problem{502, "实例模型拒绝请求，请检查服务地址、模型、密钥或限额"}
	}
	if stream != nil && strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		return readModelStream(resp.Body, func(text string) error { return stream.send("delta", text) })
	}
	var out struct {
		Choices []struct {
			Message modelMessage `json:"message"`
			Finish  string       `json:"finish_reason"`
		} `json:"choices"`
	}
	body, e := io.ReadAll(io.LimitReader(resp.Body, (2<<20)+1))
	if e != nil || len(body) > 2<<20 || json.Unmarshal(body, &out) != nil || len(out.Choices) == 0 || out.Choices[0].Message.Role != "assistant" {
		return modelMessage{}, problem{502, "实例模型响应无效"}
	}
	choice := out.Choices[0]
	if choice.Finish != "" && choice.Finish != "stop" && choice.Finish != "tool_calls" {
		return modelMessage{}, problem{502, "模型回复不完整，请重试"}
	}
	return choice.Message, nil
}
