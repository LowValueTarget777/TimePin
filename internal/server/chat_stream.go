package server

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// Returned by a handler that already wrote a complete event-stream response.
type streamResponse struct{}
type chatStream struct {
	w       http.ResponseWriter
	started bool
}

func (s *chatStream) send(kind string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if !s.started {
		s.w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
		s.w.Header().Set("Cache-Control", "no-store, no-transform")
		s.w.Header().Set("X-Accel-Buffering", "no")
		s.started = true
	}
	if _, err = fmt.Fprintf(s.w, "event: %s\ndata: %s\n\n", kind, data); err != nil {
		return err
	}
	return http.NewResponseController(s.w).Flush()
}

type toolFragment struct {
	Index int
	modelCall
}

func readModelStream(body io.Reader, delta func(string) error) (modelMessage, error) {
	bad := problem{502, "模型流中断或内容不完整，请重试"}
	limited := &io.LimitedReader{R: body, N: (2 << 20) + 1}
	scanner := bufio.NewScanner(limited)
	scanner.Buffer(make([]byte, 4096), 2<<20)
	var data, content, reasoning strings.Builder
	var calls []modelCall
	var arguments []*strings.Builder
	finish := ""
	for scanner.Scan() {
		if limited.N == 0 {
			return modelMessage{}, bad
		}
		line := scanner.Text()
		if strings.HasPrefix(line, "data:") {
			data.WriteString(strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
			data.WriteByte('\n')
			continue
		}
		if line != "" || data.Len() == 0 {
			continue
		}
		frame := strings.TrimSpace(data.String())
		data.Reset()
		if frame == "[DONE]" {
			if (len(calls) == 0 && finish != "stop") || (len(calls) > 0 && finish != "tool_calls") {
				return modelMessage{}, bad
			}
			for i := range calls {
				if calls[i].ID == "" || calls[i].Type != "function" || calls[i].Function.Name == "" {
					return modelMessage{}, bad
				}
				calls[i].Function.Arguments = arguments[i].String()
			}
			return modelMessage{Role: "assistant", Content: content.String(), Reasoning: reasoning.String(), Calls: calls}, nil
		}
		var chunk struct {
			Error   json.RawMessage
			Choices []struct {
				Index  int
				Finish string `json:"finish_reason"`
				Delta  struct {
					Content   string
					Reasoning string         `json:"reasoning_content"`
					Tools     []toolFragment `json:"tool_calls"`
				}
			}
		}
		if json.Unmarshal([]byte(frame), &chunk) != nil || len(chunk.Error) > 0 {
			return modelMessage{}, bad
		}
		for _, choice := range chunk.Choices {
			if choice.Index != 0 {
				continue
			}
			if finish != "" {
				return modelMessage{}, bad
			}
			finish = choice.Finish
			d := choice.Delta
			reasoning.WriteString(d.Reasoning)
			if d.Content != "" {
				content.WriteString(d.Content)
				if err := delta(d.Content); err != nil {
					return modelMessage{}, err
				}
			}
			for _, part := range d.Tools {
				i := part.Index
				if i < 0 || i >= 128 || i > len(calls) {
					return modelMessage{}, bad
				}
				if i == len(calls) {
					calls = append(calls, modelCall{})
					arguments = append(arguments, new(strings.Builder))
				}
				c := &calls[i]
				c.ID += part.ID
				c.Type += part.Type
				c.Function.Name += part.Function.Name
				if len(c.ID) > 512 || len(c.Type) > 32 || len(c.Function.Name) > 128 {
					return modelMessage{}, bad
				}
				arguments[i].WriteString(part.Function.Arguments)
			}
		}
	}
	return modelMessage{}, bad
}
