#  Copyright 2021-2023 cms.rendner (Daniel Schmidt)
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
from typing import Optional, Union

import numpy as np
from pandas import DataFrame, Series
from pandas.io.formats.style import _validate_apply_axis_arg

from cms_rendner_sdfv.pandas.styler.chunk_parent_provider import ChunkParentProvider
from cms_rendner_sdfv.pandas.styler.styler_todo import StylerTodo
from cms_rendner_sdfv.pandas.styler.todo_patcher import TodoPatcher


# highlight_between: https://github.com/pandas-dev/pandas/blob/v2.0.0rc0/pandas/io/formats/style.py#L3179-L3280
class HighlightBetweenPatcher(TodoPatcher):

    def __init__(self, todo: StylerTodo):
        super().__init__(todo)

    def create_patched_todo(self, org_frame: DataFrame, chunk: DataFrame) -> Optional[StylerTodo]:
        subset_frame = self._create_subset_frame(org_frame, self._todo.apply_args.subset)
        return self._todo.builder() \
            .with_subset(self._calculate_chunk_subset(subset_frame, chunk)) \
            .with_style_func(ChunkParentProvider(self._styling_func, self._todo.apply_args.axis, subset_frame)) \
            .build()

    def _styling_func(self,
                      chunk_or_series_from_chunk: Union[DataFrame, Series],
                      chunk_parent: Union[DataFrame, Series],
                      **kwargs,
                      ):
        if chunk_or_series_from_chunk.empty:
            return chunk_or_series_from_chunk

        left = kwargs.get("left", None)
        right = kwargs.get("right", None)

        # https://github.com/pandas-dev/pandas/blob/v2.0.0rc0/pandas/io/formats/style.py#L3692-L3696
        if np.iterable(left) and not isinstance(left, str):
            left = _validate_apply_axis_arg(left, "left", None, chunk_parent)
            # adjust shape of "left" to match shape of chunk
            left = self._adjust_range_part(left, chunk_or_series_from_chunk, chunk_parent)

        if np.iterable(right) and not isinstance(right, str):
            right = _validate_apply_axis_arg(right, "right", None, chunk_parent)
            # adjust shape of "right" to match shape of chunk
            right = self._adjust_range_part(right, chunk_or_series_from_chunk, chunk_parent)

        return self._todo.apply_args.style_func(
            chunk_or_series_from_chunk,
            **dict(kwargs, left=left, right=right),
        )

    def _adjust_range_part(self,
                           part: np.ndarray,
                           chunk_or_series_from_chunk: Union[DataFrame, Series],
                           chunk_parent: Union[DataFrame, Series],
                           ) -> np.ndarray:
        if isinstance(chunk_or_series_from_chunk, Series):
            return part[chunk_parent.index.get_indexer_for(chunk_or_series_from_chunk.index)]
        elif isinstance(chunk_or_series_from_chunk, DataFrame) and self._todo.apply_args.axis is None:
            ri = chunk_parent.index.get_indexer_for(chunk_or_series_from_chunk.index)
            ci = chunk_parent.columns.get_indexer_for(chunk_or_series_from_chunk.columns)
            ri_slice = slice(ri[0], ri[-1] + 1)
            ci_slice = slice(ci[0], ci[-1] + 1)
            return part[ri_slice, ci_slice]
        return part
