## eqaticim is eqatic and eqatim at once; the two are covered separately by
## 13-eqatic.t and 14-eqatim.t, so this checks that they compose.
plan(14);

# Case alone.
ok( nqp::eqaticim('aBcdef', 'bcd', 1), 'case-insensitive, no marks');
ok( nqp::eqaticim('ﬆ', 'ST', 0),       'ligature against uppercase');
ok( nqp::eqaticim('ST', 'ﬆ', 0),       'uppercase against ligature');

# Marks alone.
ok( nqp::eqaticim('á', 'a', 0),        'mark on the haystack');
ok( nqp::eqaticim('a', 'á', 0),        'mark on the needle');

# Both at once, which is what eqatic and eqatim cannot do on their own.
ok( nqp::eqaticim('Á', 'a', 0),        'uppercase with a mark against bare lowercase');
ok( nqp::eqaticim('a', 'Á', 0),        'bare lowercase against uppercase with a mark');
ok(!nqp::eqatic('Á', 'a', 0),          'eqatic alone does not ignore the mark');
ok(!nqp::eqatim('Á', 'a', 0),          'eqatim alone does not ignore the case');

# Decomposed input, not just precomposed.
ok( nqp::eqaticim("A\c[COMBINING ACUTE ACCENT]", 'a', 0), 'decomposed haystack');
ok( nqp::eqaticim('a', "A\c[COMBINING ACUTE ACCENT]", 0), 'decomposed needle');

# Offsets into the original string, and non-matches.
ok( nqp::eqaticim('aaÁaa', 'A', 2),    'offset into the original haystack');
ok(!nqp::eqaticim('b', 'bb', 0),       'needle longer than what is left');
ok(!nqp::eqaticim('st', 'ﬆa', 0),      'expanded needle runs off the end');
