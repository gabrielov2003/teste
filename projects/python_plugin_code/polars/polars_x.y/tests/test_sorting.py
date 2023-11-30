import math
from itertools import chain
from typing import List

import polars as pl
import pytest

from cms_rendner_sdfv.polars.frame_context import FrameContext

df = pl.from_dict({
    "col_0": [0, 1, 2, 3, 4],
    "col_1": [5, 6, 7, 8, 9],
    "col_2": [10, 11, 12, 13, 14],
    "col_3": [15, 16, 17, 18, 19],
    "col_4": [20, 21, 22, 23, 24],
})


def _assert_frame_sorting(
        df: pl.DataFrame,
        rows_per_chunk: int,
        cols_per_chunk: int,
        sort_by_column_index: List[int],
        sort_descending: List[bool],
):
    # create: expected
    sorted_df = df.sort(by=[df.columns[i] for i in sort_by_column_index], descending=sort_descending)
    expected_ctx = FrameContext(sorted_df)
    expected_cells = expected_ctx.get_table_frame_generator().generate().cells

    # create: actual
    actual_ctx = FrameContext(df)
    actual_ctx.set_sort_criteria(sort_by_column_index, sort_ascending=[not desc for desc in sort_descending])
    actual_cells = actual_ctx.get_table_frame_generator().generate_by_combining_chunks(
        rows_per_chunk=rows_per_chunk,
        cols_per_chunk=cols_per_chunk,
    ).cells

    assert len(list(chain(*expected_cells))) == math.prod(sorted_df.shape)
    assert actual_cells == expected_cells


@pytest.mark.parametrize(
    "sort_by, descending", [
        ([0], [False]),
        ([0, 2], [True, False]),
        ([0, 2], [True, True]),
        ([0, 2], [False, False]),
        ([4, 2, 3], [False, True, False]),
    ]
)
def test_sorting_by_multiple_columns(sort_by, descending):
    _assert_frame_sorting(
        df,
        2,
        2,
        sort_by,
        descending,
    )
