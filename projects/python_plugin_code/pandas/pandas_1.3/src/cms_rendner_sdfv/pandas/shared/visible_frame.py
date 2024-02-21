#  Copyright 2021-2024 cms.rendner (Daniel Schmidt)
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
from typing import Any, Callable, List, Tuple, Dict

import numpy as np
from pandas import DataFrame, Series

from cms_rendner_sdfv.base.table_source import AbstractVisibleFrame
from cms_rendner_sdfv.base.types import Region


class Chunk:
    def __init__(self, frame: 'VisibleFrame', region: Region):
        self._frame = frame
        self._region = region

    @property
    def region(self) -> Region:
        return self._region

    def cell_value_at(self, row_offset: int, col_offset: int) -> Any:
        return self._frame.source_frame.iloc[
            self._frame.i_rows[self.region.first_row + row_offset],
            self._frame.i_cols[self.region.first_col + col_offset],
        ]

    def column_at(self, offset: int) -> Any:
        return self._frame.source_frame.columns[self._frame.i_cols[self.region.first_col + offset]]

    def index_at(self, offset: int) -> Any:
        return self._frame.source_frame.index[self._frame.i_rows[self.region.first_row + offset]]

    def dtype_at(self, col: int) -> Any:
        return self._frame.source_frame.dtypes.iloc[self._frame.i_cols[self.region.first_col + col]]

    def describe_at(self, col: int) -> Dict[str, str]:
        s: Series = self._frame.source_frame.iloc[:, self._frame.i_cols[self.region.first_col + col]]

        def truncate(v) -> str:
            vs = str(v)
            # truncate too long values like lists
            return vs if len(vs) <= 120 else vs[:120] + '…'

        try:
            return {k: truncate(v) for k, v in s.describe().to_dict().items()}
        except TypeError as e:
            return {'error': str(e)}

    def index_names(self) -> list:
        return self._frame.source_frame.index.names

    def column_names(self) -> list:
        return self._frame.source_frame.columns.names

    def to_frame(self) -> DataFrame:
        r = self.region
        i_rows = self._frame.i_rows[r.first_row:r.first_row + r.rows]
        i_cols = self._frame.i_cols[r.first_col:r.first_col + r.cols]
        return self._frame.source_frame.iloc[i_rows, i_cols]

    def create_cell_iloc_into_org_frame_translator(self) -> Callable[[Tuple[int, int]], Tuple[int, int]]:
        r = self.region
        f = self._frame

        def translate(k: Tuple[int, int]) -> Tuple[int, int]:
            return f.i_rows[r.first_row + k[0]], f.i_cols[r.first_col + k[1]]

        return translate


class VisibleFrame(AbstractVisibleFrame):
    def __init__(self, source_frame: DataFrame, visible_rows: np.ndarray, visible_cols: np.ndarray):
        self.source_frame = source_frame
        self.i_rows = visible_rows
        self.i_cols = visible_cols
        self._region = Region(0, 0, len(visible_rows), len(visible_cols))

    @property
    def region(self) -> Region:
        return self._region

    def get_chunk(self, region: Region = None) -> Chunk:
        return Chunk(self, self._region if region is None else self.region.get_bounded_region(region))

    def get_column_indices(self, part_start: int, max_columns: int) -> List[int]:
        return list(self.i_cols[part_start:part_start + max_columns])
