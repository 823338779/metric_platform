export function placeSelection(values: string[], id: string, index = values.length) {
  const next = values.filter((value) => value !== id);
  next.splice(Math.max(0, Math.min(index, next.length)), 0, id);
  return next;
}

export function removeSelection(values: string[], id: string) {
  return values.filter((value) => value !== id);
}
