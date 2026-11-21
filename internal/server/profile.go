package server

import (
	"bytes"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"image/jpeg"
	"strings"
	"unicode"
	"unicode/utf8"
)

type personalProfile struct {
	LoginName     string `json:"loginName"`
	DisplayName   string `json:"displayName"`
	Revision      string `json:"revision"`
	AvatarVersion string `json:"avatarVersion"`
	Avatar        []byte `json:"avatar,omitempty"`
}

func loadProfile(tx *sql.Tx, u viewer, includeImage bool) (personalProfile, error) {
	p := personalProfile{LoginName: u.Name}
	imageColumn := "NULL"
	if includeImage {
		imageColumn = "avatar"
	}
	err := tx.QueryRow("SELECT display_name,revision,avatar_version,"+imageColumn+" FROM profiles WHERE user_id=?", u.ID).Scan(&p.DisplayName, &p.Revision, &p.AvatarVersion, &p.Avatar)
	if errors.Is(err, sql.ErrNoRows) {
		p.DisplayName = u.Name
		p.Revision = "0"
		err = nil
	}
	return p, err
}

func avatarBytes(encoded string) ([]byte, error) {
	if encoded == "" {
		return nil, nil
	}
	if len(encoded) > base64.StdEncoding.EncodedLen(64*1024) {
		return nil, invalid("头像过大")
	}
	b, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(b) > 64*1024 {
		return nil, invalid("无效的头像数据")
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(b))
	if err != nil || cfg.Width > 256 || cfg.Height != cfg.Width {
		return nil, invalid("头像需要 256 像素以内的正方形 JPEG")
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, invalid("无法解码头像")
	}
	var buf bytes.Buffer
	if err = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 85}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func saveProfile(tx *sql.Tx, u viewer, m management) (any, error) {
	p, err := loadProfile(tx, u, true)
	if err != nil {
		return nil, err
	}
	if m.Revision != p.Revision {
		return nil, problem{409, "资料已更新，请重新打开后编辑"}
	}
	if m.DisplayName == nil && m.Avatar == nil {
		return nil, invalid("请选择要更新的资料")
	}
	if m.DisplayName != nil {
		name := strings.TrimSpace(*m.DisplayName)
		if name == "" || utf8.RuneCountInString(name) > 40 || strings.IndexFunc(name, unicode.IsControl) >= 0 {
			return nil, invalid("昵称需要 1 至 40 个字符")
		}
		p.DisplayName = name
	}
	if m.Avatar != nil {
		p.Avatar, err = avatarBytes(*m.Avatar)
		if err != nil {
			return nil, err
		}
		p.AvatarVersion = ""
		if len(p.Avatar) > 0 {
			p.AvatarVersion = hex.EncodeToString(digest(string(p.Avatar)))
		}
	}
	// Metadata-only receipts keep avatar replacements out of the operation history.
	p.Revision = hex.EncodeToString(digest(p.DisplayName + "\x00" + p.AvatarVersion))
	_, err = tx.Exec("INSERT INTO profiles(user_id,display_name,avatar_version,revision,avatar) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET display_name=excluded.display_name, avatar=excluded.avatar, avatar_version=excluded.avatar_version, revision=excluded.revision", u.ID, p.DisplayName, p.AvatarVersion, p.Revision, p.Avatar)
	p.Avatar = nil
	return p, err
}
