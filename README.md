# No Limit Schematic Size (1회성 로드 방식)

게임의 기본 설계도 폴더에 들어 있는 파일 중, **128x128 제한 때문에 게임이 읽지 못하고 건너뛴 대형 설계도만** 골라 이번 세션에 불러옵니다.

- Windows: `%appdata%/Mindustry/schematics/`
- Linux: `~/.local/share/Mindustry/schematics/`
- macOS: `~/Library/Application Support/Mindustry/schematics/`

별도 폴더를 쓰지 않습니다. 평소처럼 설계도 폴더에 `.msch` 파일을 넣어두면 됩니다.

## 동작 순서

1. `ClientLoadEvent`(게임이 설계도 폴더를 다 읽은 뒤)에 세션당 정확히 1회 실행됩니다.
2. 이미 목록에 올라온 설계도의 파일 경로를 모읍니다. → 게임이 정상 로드한 것들.
3. 폴더에 있지만 목록에 없는 `.msch`만 자체 리더로 읽습니다.
4. 그중 `가로 > 128` 또는 `세로 > 128` 또는 `블록 수 > 128*128` 인 것만 목록에 등록합니다.
   크기와 무관한 이유로 누락된 파일(손상, 알 수 없는 버전 등)은 건드리지 않고 로그만 남깁니다.

## v1 → v2 변경점

| | v1 (이전) | v2 (현재) |
|---|---|---|
| 방식 | ByteBuddy로 `Schematics.read()` 바이트코드 **교체** | 게임 코드는 그대로, **자체 리더로 1회 로드** |
| 실행 시점 | 모드 생성자에서 즉시 self-attach | `ClientLoadEvent` 이후, 세션당 정확히 1회 |
| 의존성 | byte-buddy, byte-buddy-agent | 없음 (Mindustry API만 사용) |
| 대상 | 모든 읽기 경로 | 게임이 건너뛴 대형 파일만 |

self-attach가 막힌 환경(안드로이드, 일부 JVM 옵션)에서 실패하던 문제가 사라집니다.

## 알아둘 점

- 목록 등록은 **메모리에만** 이뤄집니다. 파일은 그대로 두므로 게임을 끄면 목록에서 사라지고, 다음 실행 때 다시 불러옵니다.
- 등록된 설계도의 `file`은 원본 `.msch`를 가리킵니다. 따라서 UI에서 이름 변경/저장하면 원본에 덮어써지고, 삭제하면 원본 파일이 지워집니다.
- 매우 큰 설계도는 미리보기 텍스처가 `(가로+여백)×4` 픽셀로 잡혀 메모리를 많이 씁니다. 렌더링에 실패하면 게임이 에러 텍스처로 대체합니다.
- 참고로 v160.1 기준 크기 검사는 `private static final boolean limitSchematicSize = true` 로 상수 인라인되어 있어, 리플렉션으로 끄는 방법은 통하지 않습니다.

## 파일 구조

- `src/NoLimitSchematicSize/NoLimitSchematicSizeMod.java` — 세션 1회 스캔/등록
- `src/NoLimitSchematicSize/LargeSchematicIO.java` — 크기 검사만 뺀 `.msch` 리더
