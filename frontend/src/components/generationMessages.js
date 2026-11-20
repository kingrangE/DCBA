export function completionMessage(type) {
  if (type === 'recommended') return '요청하신 내용과 맞는 문제가 이미 있어요. 저장한 문제에 추가했어요.'
  if (type === 'already_saved') return '요청하신 내용과 맞는 문제가 이미 저장되어 있어요.'
  return '요청한 문제가 생성되어 저장한 문제에 추가됐어요.'
}
