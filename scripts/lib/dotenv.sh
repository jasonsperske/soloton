# Sourced by repo scripts. Loads SOLOTON_* settings from a .env file without executing it.
#
#   . "$ROOT/scripts/lib/dotenv.sh"; soloton_load_dotenv "$ROOT/.env"
#
# The file is read line by line as KEY=VALUE; it is never sourced or eval'd, so nothing in it can
# run a command, and values are taken literally ($VAR and `...` are not expanded).
#   - blank lines and lines starting with # are skipped; a leading "export " is allowed
#   - only names matching SOLOTON_[A-Z0-9_]* are accepted; anything else is ignored
#   - a value may be wrapped in "double" or 'single' quotes, which are removed; an unquoted value
#     ends at a " #" comment and loses surrounding spaces
#   - a variable that is already set in the environment (even to nothing) is left alone, so
#     SOLOTON_MUSIC_DIR=/elsewhere scripts/gen1-emulator.sh overrides the file
# A missing file is not an error.

soloton_load_dotenv() {
  local file="$1" line key value
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in
      'export '*|'export'$'\t'*) line="${line#export}"; line="${line#"${line%%[![:space:]]*}"}" ;;
    esac
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    case "$key" in SOLOTON_*) ;; *) continue ;; esac
    case "$key" in *[!A-Z0-9_]*) continue ;; esac
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    case "$value" in
      '"'*'"') [ "${#value}" -ge 2 ] && { value="${value#\"}"; value="${value%\"}"; } ;;
      "'"*"'") [ "${#value}" -ge 2 ] && { value="${value#\'}"; value="${value%\'}"; } ;;
      *)
        case "$value" in '#'*) value="" ;; esac
        value="${value%%[[:space:]]#*}"
        value="${value%"${value##*[![:space:]]}"}"
        ;;
    esac
    # Already set (even if empty): the environment wins.
    if [ -n "${!key+set}" ]; then continue; fi
    export "$key=$value"
  done < "$file"
  return 0
}
