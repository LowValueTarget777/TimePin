package server

import (
	"database/sql"
	"encoding/hex"
	"net/http"
)

func createMember(tx *sql.Tx, name string, salt, hash []byte) (viewer, error) {
	member := viewer{id("u"), name}
	res, err := tx.Exec("INSERT INTO users VALUES(?,?,?,?) ON CONFLICT(name) DO NOTHING", member.ID, name, salt, hash)
	if err != nil {
		return viewer{}, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return viewer{}, err
	}
	if n == 0 {
		return viewer{}, problem{409, "该账号名已被使用"}
	}
	_, err = tx.Exec("INSERT INTO calendars VALUES(?,'个人日历','#317C72',?,NULL)", id("c"), member.ID)
	return member, err
}

func revokeMemberCredentials(tx *sql.Tx, uid string) error {
	for _, q := range []string{"DELETE FROM account_recovery WHERE user_id=?", "DELETE FROM sessions WHERE user_id=?", "DELETE FROM api_tokens WHERE user_id=?"} {
		if _, err := tx.Exec(q, uid); err != nil {
			return err
		}
	}
	return nil
}

// consoleManage authenticates before the costly password work. Recheck the session
// inside the write transaction before issuing a receipt or making any changes.
func (a *App) consoleCredential(r *http.Request, m management) (any, error) {
	if !validPassword(m.Password) || m.Op == "instance.accountCreate" && !validName(m.Name) {
		return nil, invalid("请输入有效的账号名和至少 10 个字符、至多 256 字节的密码")
	}
	ctx := r.Context()
	m.salt = randBytes(16)
	hash, err := a.hash(ctx, m.Password, m.salt)
	if err != nil {
		return nil, err
	}
	m.passwordHash = hash
	// A stolen receipt must not offer a cheap offline password check.
	proof, err := a.hash(ctx, m.Password, digest("timepin:console-receipt:"+m.OperationID))
	if err != nil {
		return nil, err
	}
	m.Password = hex.EncodeToString(proof)
	return transaction(ctx, a.DB, func(tx *sql.Tx) (any, error) {
		u, err := consoleIdentity(tx, r)
		if err != nil {
			return nil, err
		}
		return a.manage(tx, u, m)
	})
}
