#  Copyright 2022 cms.rendner (Daniel Schmidt)
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

from plugin_code.chunk_parent_provider import ChunkParentProvider
from plugin_code.styler_todo import StylerTodo
from plugin_code.todo_patcher import TodoPatcher

# == copy after here ==
import inspect
from typing import Optional, Union

from pandas import DataFrame, Series


class ApplyPatcher(TodoPatcher):

    def __init__(self, df: DataFrame, todo: StylerTodo):
        super().__init__(df, todo)

    def create_patched_todo(self, chunk: DataFrame) -> Optional[StylerTodo]:
        if self._should_provide_chunk_parent():
            return self._todo.copy_with(
                apply_args_subset=self._calculate_chunk_subset(chunk),
                style_func=ChunkParentProvider(
                    self._styling_func,
                    self._todo.apply_args.axis,
                    self._subset_data,
                )
            )
        else:
            return self._todo.copy_with(
                apply_args_subset=self._calculate_chunk_subset(chunk),
                style_func=self._styling_func,
            )

    def _styling_func(self,
                      chunk_or_series_from_chunk: Union[DataFrame, Series],
                      **kwargs,
                      ):
        if chunk_or_series_from_chunk.empty:
            return chunk_or_series_from_chunk

        return self._todo.apply_args.style_func(chunk_or_series_from_chunk, **kwargs)

    def _should_provide_chunk_parent(self):
        sig = inspect.signature(self._todo.apply_args.style_func)
        for param in sig.parameters.values():
            if param.name == "chunk_parent" or param.kind == inspect.Parameter.VAR_KEYWORD:
                return True
        return False