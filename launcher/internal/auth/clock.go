package auth

import "time"

// timeNowUnix 返回当前 unix 秒。独立小函数便于在测试里替换 nowUnix。
func timeNowUnix() int64 { return time.Now().Unix() }
