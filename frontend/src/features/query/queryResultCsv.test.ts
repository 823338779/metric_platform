import { expect, it } from 'vitest';
import { queryResultCsv } from './queryResultCsv';

it('escapes CSV headers and values', () => {
  expect(
    queryResultCsv({
      columns: [
        { name: 'Customer, name', type: 'VARCHAR' },
        { name: 'Note', type: 'VARCHAR' },
      ],
      rows: [
        ['Alice', 'said "hi"'],
        [null, 'line\nbreak'],
      ],
    }),
  ).toBe('\uFEFF"Customer, name",Note\r\nAlice,"said ""hi"""\r\n,"line\nbreak"');
});
